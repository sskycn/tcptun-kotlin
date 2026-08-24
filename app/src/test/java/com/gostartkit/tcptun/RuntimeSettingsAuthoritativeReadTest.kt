package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsAuthoritativeReadTest {
    @Test
    fun cleanInstallReturnsAuthoritativeDefaultsWithoutWritingStorage() {
        val preferences = FakeRuntimeSettingsPreferences()
        val result = engine(preferences).read()

        assertTrue(result is RuntimeSettingsRead.Success)
        result as RuntimeSettingsRead.Success
        assertEquals(RuntimeSettingsSource.CleanInstall, result.source)
        assertEquals(RuntimeSettings(), result.settings)
        assertTrue(preferences.values.isEmpty())
    }

    @Test
    fun validEncryptedSettingsPreserveCredentials() {
        val preferences = encryptedPreferences()
        val secrets = FakeSecretStorage().apply {
            values[SecretId] = "real-user\u0000real-secret"
        }

        val result = engine(preferences, secrets).read() as RuntimeSettingsRead.Success

        assertEquals("real-user", result.settings.socksUsername)
        assertEquals("real-secret", result.settings.socksPassword)
    }

    @Test
    fun missingEncryptedSecretIsUnavailableInsteadOfDefaults() {
        val result = engine(encryptedPreferences(), FakeSecretStorage()).read()

        assertTrue(result is RuntimeSettingsRead.Unavailable)
    }

    @Test
    fun encryptedPointerWithoutStorageVersionIsCorruptionNotCleanInstall() {
        val preferences = FakeRuntimeSettingsPreferences().apply {
            values[RuntimeSettingsStorageKeys.SecretsId] = SecretId
        }

        assertTrue(engine(preferences).read() is RuntimeSettingsRead.Unavailable)
    }

    @Test
    fun corruptEnvelopeIsUnavailable() {
        val secrets = FakeSecretStorage(readFailure = IllegalArgumentException("corrupt envelope"))

        assertTrue(engine(encryptedPreferences(), secrets).read() is RuntimeSettingsRead.Unavailable)
    }

    @Test
    fun secretAuthenticationFailureIsUnavailable() {
        val secrets = FakeSecretStorage(readFailure = SecurityException("authentication failed"))

        assertTrue(engine(encryptedPreferences(), secrets).read() is RuntimeSettingsRead.Unavailable)
    }

    @Test
    fun malformedSecretJsonIsUnavailable() {
        val secrets = FakeSecretStorage().apply { values[SecretId] = "malformed" }

        assertTrue(engine(encryptedPreferences(), secrets).read() is RuntimeSettingsRead.Unavailable)
    }

    @Test
    fun legacyMigrationFailureKeepsPlaintextForRetry() {
        val preferences = FakeRuntimeSettingsPreferences().apply {
            values[RuntimeSettingsStorageKeys.SocksUsername] = "legacy-user"
            values[RuntimeSettingsStorageKeys.SocksPassword] = "legacy-secret"
        }
        val secrets = FakeSecretStorage(writeFailure = IllegalStateException("keystore unavailable"))

        val result = engine(preferences, secrets).read()

        assertTrue(result is RuntimeSettingsRead.Unavailable)
        assertEquals("legacy-user", preferences.values[RuntimeSettingsStorageKeys.SocksUsername])
        assertEquals("legacy-secret", preferences.values[RuntimeSettingsStorageKeys.SocksPassword])
        assertFalse(preferences.values.containsKey(RuntimeSettingsStorageKeys.StorageVersion))
    }

    @Test
    fun retryCanBecomeAuthoritativeAfterTransientSecretFailure() {
        val secrets = FakeSecretStorage(readFailure = SecurityException("temporarily unavailable")).apply {
            values[SecretId] = "user\u0000secret"
        }
        val repository = engine(encryptedPreferences(), secrets)

        assertTrue(repository.read() is RuntimeSettingsRead.Unavailable)
        secrets.readFailure = null
        val retried = repository.read()

        assertTrue(retried is RuntimeSettingsRead.Success)
        assertEquals("secret", (retried as RuntimeSettingsRead.Success).settings.socksPassword)
    }

    private fun encryptedPreferences() = FakeRuntimeSettingsPreferences().apply {
        values[RuntimeSettingsStorageKeys.StorageVersion] = RuntimeSettingsStorageKeys.EncryptedSecretsVersion
        values[RuntimeSettingsStorageKeys.SecretsId] = SecretId
        values[RuntimeSettingsStorageKeys.Mtu] = RuntimeSettingsDefaults.VpnMtu
        values[RuntimeSettingsStorageKeys.PowerSaving] = true
        values[RuntimeSettingsStorageKeys.LogLevel] = DefaultLogLevel
        values[RuntimeSettingsStorageKeys.SocksPort] = RuntimeSettingsDefaults.SocksPort
        values[RuntimeSettingsStorageKeys.LocalProxyProtocol] = DefaultLocalProxyProtocol
        values[RuntimeSettingsStorageKeys.SocksListenAll] = false
        values[RuntimeSettingsStorageKeys.RouteLocalProxyTraffic] = false
        values[RuntimeSettingsStorageKeys.DefaultOutbound] = DefaultOutboundDynamicPool
        values[RuntimeSettingsStorageKeys.FlowAnalysisApp] = ""
    }

    private fun engine(
        preferences: FakeRuntimeSettingsPreferences,
        secrets: FakeSecretStorage = FakeSecretStorage(),
    ) = RuntimeSettingsRepositoryEngine(
        preferences,
        secrets,
        nextSecretId = { "runtime.next" },
        credentialCodec = FakeRuntimeSettingsCredentialCodec,
    )

    private companion object {
        const val SecretId = "runtime.current"
    }
}

internal object FakeRuntimeSettingsCredentialCodec : RuntimeSettingsCredentialCodec {
    override fun encode(username: String, password: String): String = "$username\u0000$password"

    override fun decode(raw: String): Pair<String, String> {
        val parts = raw.split('\u0000', limit = 2)
        require(parts.size == 2) { "malformed credential payload" }
        return parts[0] to parts[1]
    }
}

internal class FakeRuntimeSettingsPreferences : RuntimeSettingsPreferences {
    val values = linkedMapOf<String, Any>()
    var publishFailure = false

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun getInt(key: String, defaultValue: Int): Int =
        values[key]?.let { it as Int } ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key]?.let { it as Boolean } ?: defaultValue
    override fun getString(key: String, defaultValue: String?): String? =
        values[key]?.let { it as String } ?: defaultValue

    override fun publish(settings: RuntimeSettings, secretsId: String): Boolean {
        if (publishFailure) return false
        values[RuntimeSettingsStorageKeys.StorageVersion] = RuntimeSettingsStorageKeys.EncryptedSecretsVersion
        values[RuntimeSettingsStorageKeys.Mtu] = settings.mtu
        values[RuntimeSettingsStorageKeys.PowerSaving] = settings.powerSavingMode
        values[RuntimeSettingsStorageKeys.LogLevel] = settings.logLevel
        values[RuntimeSettingsStorageKeys.SocksPort] = settings.socksPort
        values[RuntimeSettingsStorageKeys.LocalProxyProtocol] = settings.localProxyProtocol
        values[RuntimeSettingsStorageKeys.SocksListenAll] = settings.socksListenAll
        values[RuntimeSettingsStorageKeys.SecretsId] = secretsId
        values[RuntimeSettingsStorageKeys.RouteLocalProxyTraffic] = settings.routeLocalProxyTraffic
        values[RuntimeSettingsStorageKeys.DefaultOutbound] = settings.defaultOutbound
        values[RuntimeSettingsStorageKeys.FlowAnalysisApp] = settings.flowAnalysisApp
        values.remove(RuntimeSettingsStorageKeys.SocksUsername)
        values.remove(RuntimeSettingsStorageKeys.SocksPassword)
        return true
    }
}

internal class FakeSecretStorage(
    var readFailure: Throwable? = null,
    var writeFailure: Throwable? = null,
) : SecretStorage {
    val values = linkedMapOf<String, String>()

    override fun writeVerified(key: String, plaintext: String) {
        writeFailure?.let { throw it }
        values[key] = plaintext
    }

    override fun read(key: String): String? {
        readFailure?.let { throw it }
        return values[key]
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
