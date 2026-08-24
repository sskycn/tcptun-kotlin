package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnCommandStoreAndroidTest {
    @Test
    fun encryptedCommandIsConsumedExactlyOnceWithoutPlaintextAtRest() {
        val secrets = CiphertextSecretStorage()
        val metadata = FakeMetadataStorage()
        val store = EncryptedVpnCommandStore(secrets, metadata)
        val profile = AppConfig(
            id = "command-profile",
            serverHost = "command-secret.example",
            serverPort = "443",
            protocol = "native",
            token = "command-secret-token",
        )
        val payload = StartVpnCommandPayload(
            configJson = "{\"token\":\"command-secret-config\"}",
            plan = ProfileRunPlan(listOf(profile), setOf(profile.id)),
            runtimeSettings = RuntimeSettings(socksPassword = "command-secret-password"),
        )

        val commandId = store.publish(payload)

        val persisted = secrets.envelopes.values.joinToString()
        listOf(profile.token, profile.serverHost, "command-secret-config", "command-secret-password")
            .forEach { assertFalse(persisted.contains(it)) }
        assertEquals(payload, store.consume(commandId))
        assertNull(store.consume(commandId))
        assertTrue(secrets.envelopes.isEmpty())
        assertTrue(metadata.values.isEmpty())
    }

    @Test
    fun staleCommandIsCleanedAndCannotBeConsumed() {
        var now = 1_000_000L
        val secrets = CiphertextSecretStorage()
        val metadata = FakeMetadataStorage()
        val store = EncryptedVpnCommandStore(secrets, metadata, nowMillis = { now })
        val commandId = store.publish(UpdateOutboundsCommandPayload(ProfileRunPlan(emptyList())))

        now += EncryptedVpnCommandStore.CommandTtlMillis + 1
        store.cleanupExpired()

        assertNull(store.consume(commandId))
        assertTrue(secrets.envelopes.isEmpty())
        assertTrue(metadata.values.isEmpty())
    }

    private class CiphertextSecretStorage : SecretStorage {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val cipher = AesGcmSecretCipher({ key })
        val envelopes = mutableMapOf<String, String>()

        override fun writeVerified(key: String, plaintext: String) {
            envelopes[key] = cipher.encrypt(plaintext, key)
            check(read(key) == plaintext)
        }

        override fun read(key: String): String? = envelopes[key]?.let { cipher.decrypt(it, key) }

        override fun remove(key: String) {
            envelopes.remove(key)
        }
    }

    private class FakeMetadataStorage : VpnCommandMetadataStorage {
        val values = mutableMapOf<String, Long>()

        override fun put(commandId: String, createdAtMillis: Long): Boolean {
            values[commandId] = createdAtMillis
            return true
        }

        override fun read(commandId: String): Long? = values[commandId]
        override fun entries(): Map<String, Long> = values.toMap()

        override fun remove(commandId: String): Boolean {
            values.remove(commandId)
            return true
        }
    }
}
