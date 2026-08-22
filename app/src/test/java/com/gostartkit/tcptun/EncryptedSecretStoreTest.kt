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
    fun failedEncryptedWriteNeverDeletesLegacyPlaintext() {
        var plaintextPresent = true

        assertThrows(IllegalStateException::class.java) {
            afterVerifiedSecretWrite(
                writeAndVerify = { throw IllegalStateException("keystore unavailable") },
                replacePlaintext = { plaintextPresent = false },
            )
        }

        assertTrue(plaintextPresent)
    }
}
