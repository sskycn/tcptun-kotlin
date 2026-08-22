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
}
