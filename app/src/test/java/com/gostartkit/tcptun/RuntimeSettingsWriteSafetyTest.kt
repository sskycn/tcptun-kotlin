package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsWriteSafetyTest {
    @Test
    fun failedReadCannotOverwriteStoredCredentialsWithPartialDraft() {
        val preferences = FakeRuntimeSettingsPreferences().apply {
            values[RuntimeSettingsStorageKeys.StorageVersion] =
                RuntimeSettingsStorageKeys.EncryptedSecretsVersion
            values[RuntimeSettingsStorageKeys.SecretsId] = CurrentSecretId
            values[RuntimeSettingsStorageKeys.Mtu] = 1400
            values[RuntimeSettingsStorageKeys.PowerSaving] = true
            values[RuntimeSettingsStorageKeys.LogLevel] = DefaultLogLevel
            values[RuntimeSettingsStorageKeys.SocksPort] = RuntimeSettingsDefaults.SocksPort
            values[RuntimeSettingsStorageKeys.LocalProxyProtocol] = DefaultLocalProxyProtocol
            values[RuntimeSettingsStorageKeys.SocksListenAll] = false
            values[RuntimeSettingsStorageKeys.RouteLocalProxyTraffic] = false
            values[RuntimeSettingsStorageKeys.DefaultOutbound] = DefaultOutboundDynamicPool
            values[RuntimeSettingsStorageKeys.FlowAnalysisApp] = ""
        }
        val secrets = FakeSecretStorage().apply {
            values[CurrentSecretId] = "real-user\u0000real-secret"
        }
        val repository = RuntimeSettingsRepositoryEngine(
            preferences,
            secrets,
            nextSecretId = { "runtime.next" },
            credentialCodec = FakeRuntimeSettingsCredentialCodec,
        )
        val authoritative = repository.read() as RuntimeSettingsRead.Success
        assertEquals("real-secret", authoritative.settings.localProxyUsers.single().password)
        secrets.readFailure = SecurityException("keystore unavailable")
        val unavailable = repository.read()

        assertThrows(IllegalStateException::class.java) {
            repository.writeIfCurrent(unavailable, RuntimeSettings(mtu = 1360))
        }
        secrets.readFailure = null
        assertEquals(
            "real-secret",
            (repository.read() as RuntimeSettingsRead.Success).settings.localProxyUsers.single().password,
        )
        assertTrue(secrets.values.containsKey(CurrentSecretId))
    }

    @Test
    fun staleAuthoritativeRevisionCannotOverwriteNewerCredentials() {
        val preferences = FakeRuntimeSettingsPreferences()
        val secrets = FakeSecretStorage()
        var next = 0
        val repository = RuntimeSettingsRepositoryEngine(
            preferences,
            secrets,
            nextSecretId = { "runtime.${++next}" },
            credentialCodec = FakeRuntimeSettingsCredentialCodec,
        )
        val clean = repository.read()
        val first = repository.writeIfCurrent(
            clean,
            RuntimeSettings(localProxyUsers = listOf(LocalProxyUser("user", "first-secret"))),
        )
        repository.writeIfCurrent(first, first.settings.copy(localProxyUsers = listOf(LocalProxyUser("user", "newer-secret"))))

        assertThrows(IllegalStateException::class.java) {
            repository.writeIfCurrent(first, first.settings.copy(mtu = 1360))
        }
        assertEquals(
            "newer-secret",
            (repository.read() as RuntimeSettingsRead.Success).settings.localProxyUsers.single().password,
        )
    }

    private companion object {
        const val CurrentSecretId = "runtime.current"
    }
}
