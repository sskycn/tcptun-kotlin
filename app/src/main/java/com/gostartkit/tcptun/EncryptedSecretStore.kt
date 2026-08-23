package com.tcptun.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SecretCipher {
    fun encrypt(plaintext: String, associatedData: String): String
    fun decrypt(envelope: String, associatedData: String): String
}

internal interface SecretStorage {
    fun writeVerified(key: String, plaintext: String)
    fun read(key: String): String?
    fun remove(key: String)
}

/**
 * Publishes a fresh encrypted blob only after read-back verification. If either the encrypted
 * write or the public pointer commit fails, the unreferenced fresh blob is removed. The caller
 * remains responsible for deleting the previous blob, and must do that only after this returns
 * true.
 */
internal fun replaceWithVerifiedSecret(
    secretStore: SecretStorage,
    newSecretId: String,
    plaintext: String,
    commitPointer: () -> Boolean,
): Boolean {
    try {
        secretStore.writeVerified(newSecretId, plaintext)
        val committed = commitPointer()
        if (!committed) bestEffortRemoveSecret(secretStore, newSecretId)
        return committed
    } catch (error: Throwable) {
        bestEffortRemoveSecret(secretStore, newSecretId)
        throw error
    }
}

private fun bestEffortRemoveSecret(secretStore: SecretStorage, secretId: String) {
    try {
        secretStore.remove(secretId)
    } catch (cleanupError: Throwable) {
        if (cleanupError.isFatalProcessError()) throw cleanupError
    }
}

/** AES-GCM envelope shared by the Android Keystore implementation and JVM tests. */
internal class AesGcmSecretCipher(
    private val keyProvider: () -> SecretKey,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SecretCipher {
    override fun encrypt(plaintext: String, associatedData: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), secureRandom)
        val iv = requireNotNull(cipher.iv).also {
            require(it.size == IV_BYTES) { "invalid generated encryption IV" }
        }
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return "$ENVELOPE_VERSION:${iv.toHex()}:${ciphertext.toHex()}"
    }

    override fun decrypt(envelope: String, associatedData: String): String {
        val parts = envelope.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == ENVELOPE_VERSION) { "unsupported encrypted data" }
        val iv = parts[1].decodeHex()
        require(iv.size == IV_BYTES) { "invalid encrypted data IV" }
        val ciphertext = parts[2].decodeHex()
        require(ciphertext.size >= TAG_BITS / 8) { "invalid encrypted data payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        HEX[(byte.toInt() ushr 4) and 0xf].toString() + HEX[byte.toInt() and 0xf]
    }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "invalid encrypted data encoding" }
        return ByteArray(length / 2) { index ->
            val high = this[index * 2].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid encrypted data encoding")
            val low = this[index * 2 + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid encrypted data encoding")
            ((high shl 4) or low).toByte()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENVELOPE_VERSION = "v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val HEX = "0123456789abcdef"
    }
}

/** Creates and uses a non-exportable AES key owned by Android Keystore. */
internal object AndroidKeystoreSecretCipher : SecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tcptun.settings.secrets.v1"
    private val delegate = AesGcmSecretCipher(::getOrCreateKey)

    override fun encrypt(plaintext: String, associatedData: String): String =
        delegate.encrypt(plaintext, associatedData)

    override fun decrypt(envelope: String, associatedData: String): String =
        delegate.decrypt(envelope, associatedData)

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

internal class EncryptedSecretStore(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreSecretCipher,
) : SecretStorage {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun writeVerified(key: String, plaintext: String) {
        require(key.isNotBlank()) { "secret key must not be blank" }
        val encrypted = cipher.encrypt(plaintext, key)
        check(preferences.edit().putString(key, encrypted).commit()) {
            "encrypted data could not be persisted"
        }
        check(read(key) == plaintext) { "encrypted data read-back verification failed" }
    }

    override fun read(key: String): String? = preferences.getString(key, null)?.let {
        cipher.decrypt(it, key)
    }

    override fun remove(key: String) {
        if (key.isNotBlank()) check(preferences.edit().remove(key).commit()) {
            "obsolete encrypted data could not be removed"
        }
    }

    private companion object {
        const val PREFERENCES = "tcptun_encrypted_secrets"
    }
}
