package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsSecurityTest {
    @Test
    fun loopbackListenerAllowsEmptyAuthentication() {
        assertEquals(RuntimeSettings(), requireSafeRuntimeSettings(RuntimeSettings()))
    }

    @Test
    fun lanListenerWithConfiguredPasswordIsAllowed() {
        val settings = RuntimeSettings(socksListenAll = true, socksPassword = "configured")

        assertEquals(settings, requireSafeRuntimeSettings(settings))
    }

    @Test
    fun unsafeLanListenerIsRepairedOrRejectedAtEveryRuntimeBoundary() {
        val unsafe = RuntimeSettings(socksListenAll = true)
        val repaired = secureRuntimeSettings(unsafe)

        assertTrue(repaired.socksPassword.isNotEmpty())
        assertEquals(repaired, requireSafeRuntimeSettings(repaired))
        assertThrows(IllegalArgumentException::class.java) { requireSafeRuntimeSettings(unsafe) }
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeAppliedRuntimeSettings(AppliedRuntimeSettings.from(unsafe))
        }
    }

    @Test
    fun generatedLanPasswordsHave192BitsOfUrlSafeEntropyAndDiffer() {
        val first = generateLanProxyPassword()
        val second = generateLanProxyPassword()

        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertNotEquals(first, second)
    }

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
