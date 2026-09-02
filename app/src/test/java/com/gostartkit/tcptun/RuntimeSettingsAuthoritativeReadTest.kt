package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsAuthoritativeReadTest {
    @Test
    fun twoAccountsRoundTripThroughEncryptedStorage() {
        val preferences = FakeRuntimeSettingsPreferences()
        val secrets = FakeSecretStorage()
        val repository = engine(preferences, secrets)
        val users = listOf(LocalProxyUser("alice", "secret-a"), LocalProxyUser("bob", "secret-b"))

        repository.write(RuntimeSettings(localProxyUsers = users))

        assertEquals(users, (repository.read() as RuntimeSettingsRead.Success).settings.localProxyUsers)
        assertFalse(preferences.values.values.any { value -> users.any { value.toString().contains(it.password) } })
    }

    @Test
    fun accountCountAndDuplicateUsernameMatchGoBoundary() {
        val users = List(MaxLocalProxyUsers) { LocalProxyUser("user-$it", "secret") }
        assertEquals(users, requireSafeRuntimeSettings(RuntimeSettings(localProxyUsers = users)).localProxyUsers)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            requireSafeRuntimeSettings(RuntimeSettings(localProxyUsers = users + LocalProxyUser("overflow", "secret")))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            requireSafeRuntimeSettings(
                RuntimeSettings(localProxyUsers = listOf(LocalProxyUser("same", "a"), LocalProxyUser("same", "b"))),
            )
        }
    }

    @Test
    fun versionTwoSingleAccountPayloadMigratesOnNextSuccessfulWrite() {
        val preferences = encryptedPreferences().apply {
            values[RuntimeSettingsStorageKeys.StorageVersion] = RuntimeSettingsStorageKeys.LegacyEncryptedSecretsVersion
        }
        val secrets = FakeSecretStorage().apply { values[SecretId] = "legacy-user\u0000legacy-secret" }
        val repository = engine(preferences, secrets)

        val loaded = repository.read() as RuntimeSettingsRead.Success
        assertEquals(listOf(LocalProxyUser("legacy-user", "legacy-secret")), loaded.settings.localProxyUsers)
        repository.writeIfCurrent(loaded, loaded.settings.copy(mtu = 1360))

        assertEquals(RuntimeSettingsStorageKeys.EncryptedSecretsVersion, preferences.values[RuntimeSettingsStorageKeys.StorageVersion])
        assertFalse(secrets.values.containsKey(SecretId))
    }

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

        assertEquals(listOf(LocalProxyUser("real-user", "real-secret")), result.settings.localProxyUsers)
    }

    @Test
    fun encryptedSettingsFromOlderSchemaUseDefaultsForMissingPublicFields() {
        val preferences = encryptedPreferences().apply {
            values.remove(RuntimeSettingsStorageKeys.PowerSaving)
            values.remove(RuntimeSettingsStorageKeys.LocalProxyProtocol)
            values.remove(RuntimeSettingsStorageKeys.FlowAnalysisApp)
        }
        val secrets = FakeSecretStorage().apply {
            values[SecretId] = "real-user\u0000real-secret"
        }

        val result = engine(preferences, secrets).read() as RuntimeSettingsRead.Success

        assertEquals(true, result.settings.powerSavingMode)
        assertEquals(DefaultLocalProxyProtocol, result.settings.localProxyProtocol)
        assertEquals("", result.settings.flowAnalysisApp)
        assertEquals(AndroidVpnRoutePlan.FullTunnel, result.settings.vpnRoutePlan)
        assertEquals(listOf(LocalProxyUser("real-user", "real-secret")), result.settings.localProxyUsers)
    }

    @Test
    fun legacySplitRoutePlanIsRetiredAndLoadsAsFullTunnel() {
        val preferences = encryptedPreferences().apply {
            values[RuntimeSettingsStorageKeys.VpnRoutePlan] =
                """{"mode":"split","routes":["192.168.50.0/24"],"dnsServers":["192.168.50.1"]}"""
        }
        val secrets = FakeSecretStorage().apply {
            values[SecretId] = "real-user\u0000real-secret"
        }

        val result = engine(preferences, secrets).read() as RuntimeSettingsRead.Success

        assertEquals(AndroidVpnRoutePlan.FullTunnel, result.settings.vpnRoutePlan)
        assertFalse(preferences.values.containsKey(RuntimeSettingsStorageKeys.VpnRoutePlan))
        assertEquals(SecretId, preferences.values[RuntimeSettingsStorageKeys.SecretsId])
        assertEquals("real-user\u0000real-secret", secrets.values[SecretId])
    }

    @Test
    fun encryptedAnonymousLanListenerIsRepairedBeforeBecomingAuthoritative() {
        val preferences = encryptedPreferences().apply {
            values[RuntimeSettingsStorageKeys.SocksListenAll] = true
        }
        val secrets = FakeSecretStorage().apply {
            values[SecretId] = "\u0000"
        }

        val result = engine(preferences, secrets).read() as RuntimeSettingsRead.Success

        assertTrue(result.settings.socksListenAll)
        assertTrue(result.settings.localProxyUsers.single().password.isNotEmpty())
        assertEquals("runtime.next", preferences.values[RuntimeSettingsStorageKeys.SecretsId])
        assertFalse(secrets.values.containsKey(SecretId))
        assertTrue(secrets.values.getValue("runtime.next").substringAfter('\u0000').isNotEmpty())
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
        assertEquals("secret", (retried as RuntimeSettingsRead.Success).settings.localProxyUsers.single().password)
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
    override fun encode(users: List<LocalProxyUser>): String = users.joinToString("\u0001") {
        "${it.username}\u0000${it.password}"
    }

    override fun decode(raw: String): List<LocalProxyUser> {
        if (raw.isEmpty()) return emptyList()
        return raw.split('\u0001').map { encoded ->
            val parts = encoded.split('\u0000', limit = 2)
            require(parts.size == 2) { "malformed credential payload" }
            if (parts[0].isEmpty() && parts[1].isEmpty()) return emptyList()
            LocalProxyUser(parts[0], parts[1])
        }
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
        values.remove(RuntimeSettingsStorageKeys.VpnRoutePlan)
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
