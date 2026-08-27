package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageUnavailableStateTest {
    @Test
    fun unavailableReadDisablesMutationAndCannotHydrateCredentials() {
        val unavailable = RuntimeSettingsRead.Unavailable(SecurityException("keystore unavailable"))

        assertFalse(unavailable.allowsMutation())
        assertThrows(IllegalStateException::class.java) {
            unavailable.requireAuthoritativeSettings()
        }
    }

    @Test
    fun authoritativeReadEnablesMutation() {
        val success = RuntimeSettingsRead.Success(
            RuntimeSettings(localProxyUsers = listOf(LocalProxyUser("", "secret"))),
            RuntimeSettingsSource.Stored,
            RuntimeSettingsRevision.Stored("opaque"),
        )

        assertTrue(success.allowsMutation())
    }
}
