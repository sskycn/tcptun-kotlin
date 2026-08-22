package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsSecurityTest {
    @Test
    fun loopbackListenerAllowsEmptyPassword() {
        val secured = secureRuntimeSettings(RuntimeSettings(socksListenAll = false))

        assertFalse(secured.generatedLanProxyPassword)
        assertEquals("", secured.settings.socksPassword)
        requireSafeRuntimeSettings(secured.settings)
    }

    @Test
    fun lanListenerAllowsConfiguredPassword() {
        val secured = secureRuntimeSettings(
            RuntimeSettings(socksListenAll = true, socksPassword = "configured-secret"),
        )

        assertFalse(secured.generatedLanProxyPassword)
        assertEquals("configured-secret", secured.settings.socksPassword)
        requireSafeRuntimeSettings(secured.settings)
    }

    @Test
    fun lanListenerWithoutPasswordIsRepaired() {
        val secured = secureRuntimeSettings(RuntimeSettings(socksListenAll = true)) { "generated-secret" }

        assertTrue(secured.generatedLanProxyPassword)
        assertEquals("generated-secret", secured.settings.socksPassword)
        requireSafeRuntimeSettings(secured.settings)
    }

    @Test
    fun unsafeLanListenerIsRejectedAtValidationBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeRuntimeSettings(RuntimeSettings(socksListenAll = true))
        }
    }

    @Test
    fun generatedPasswordsAreNonEmptyUrlSafeAndDifferent() {
        val first = generateLanProxyPassword()
        val second = generateLanProxyPassword()

        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
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
        var generated = false

        val secured = secureRuntimeSettings(
            hydrateRuntimeSettingsCredentials(restoredDraft, persisted),
        ) {
            generated = true
            "replacement-password"
        }

        assertFalse(generated)
        assertFalse(secured.generatedLanProxyPassword)
        assertEquals("stable-lan-password", secured.settings.socksPassword)
        assertEquals(1360, secured.settings.mtu)
    }
}
