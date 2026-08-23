package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSettingsSecurityTest {
    @Test
    fun restoredNonSecretDraftRehydratesPersistedCredentialsBeforeUnrelatedSave() {
        val persisted = RuntimeSettings(
            mtu = 1400,
            logLevel = "info",
            socksUsername = "persisted-user",
            socksPassword = "persisted-password",
        )
        val restoredDraft = persisted.copy(
            mtu = 1360,
            logLevel = "debug",
            socksUsername = "",
            socksPassword = "",
        )

        val savedAfterRecreation = hydrateRuntimeSettingsCredentials(restoredDraft, persisted)
            .copy(mtu = 1500)

        assertEquals(1500, savedAfterRecreation.mtu)
        assertEquals("debug", savedAfterRecreation.logLevel)
        assertEquals("persisted-user", savedAfterRecreation.socksUsername)
        assertEquals("persisted-password", savedAfterRecreation.socksPassword)
    }

    @Test
    fun lanCredentialHydrationDoesNotGenerateAReplacementPassword() {
        val persisted = RuntimeSettings(
            socksListenAll = true,
            socksUsername = "lan-user",
            socksPassword = "stable-lan-password",
        )
        val restoredDraft = persisted.copy(
            mtu = 1360,
            socksUsername = "",
            socksPassword = "",
        )
        val hydrated = hydrateRuntimeSettingsCredentials(restoredDraft, persisted)

        assertEquals("stable-lan-password", hydrated.socksPassword)
        assertEquals(1360, hydrated.mtu)
    }
}
