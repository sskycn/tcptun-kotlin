package com.tcptun.client

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedSecretStoreTest {
    @Test
    fun encryptedPayloadRoundTripsWithoutContainingPlaintext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmSecretCipher({ key })

        val encrypted = cipher.encrypt("top-secret-token", "profiles:test")

        assertFalse(encrypted.contains("top-secret-token"))
        assertEquals("top-secret-token", cipher.decrypt(encrypted, "profiles:test"))
    }

    @Test
    fun eachWriteUsesANewRandomIv() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmSecretCipher({ key })

        assertNotEquals(cipher.encrypt("same", "key"), cipher.encrypt("same", "key"))
    }

    @Test
    fun corruptedCiphertextAndWrongAssociatedDataFailClosed() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmSecretCipher({ key })
        val encrypted = cipher.encrypt("secret", "correct-key")
        val corrupted = encrypted.dropLast(1) + if (encrypted.last() == '0') '1' else '0'

        assertThrows(Exception::class.java) { cipher.decrypt(corrupted, "correct-key") }
        assertThrows(Exception::class.java) { cipher.decrypt(encrypted, "wrong-key") }
    }

    @Test
    fun failedEncryptedWriteNeverCommitsPublicReplacement() {
        val store = RecordingSecretStorage(failWrite = true)
        var replacementCommitted = false
        var legacyPlaintext: String? = "legacy-plan"

        assertThrows(IllegalStateException::class.java) {
            replaceWithVerifiedSecret(
                secretStore = store,
                newSecretId = "new",
                plaintext = "secret",
                commitPointer = {
                    replacementCommitted = true
                    legacyPlaintext = null
                    true
                },
            )
        }

        assertFalse(replacementCommitted)
        assertEquals("legacy-plan", legacyPlaintext)
        assertFalse(store.values.containsKey("new"))
    }

    private class RecordingSecretStorage(
        private val failWrite: Boolean,
    ) : SecretStorage {
        val values = mutableMapOf<String, String>()

        override fun writeVerified(key: String, plaintext: String) {
            values[key] = plaintext
            if (failWrite) throw IllegalStateException("keystore unavailable")
        }

        override fun read(key: String): String? = values[key]

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
