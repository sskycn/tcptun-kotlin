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
        val settings = RuntimeSettings(socksListenAll = true, localProxyUsers = listOf(LocalProxyUser("", "configured")))

        assertEquals(settings, requireSafeRuntimeSettings(settings))
    }

    @Test
    fun unsafeLanListenerIsRepairedOrRejectedAtEveryRuntimeBoundary() {
        val unsafe = RuntimeSettings(socksListenAll = true)
        val repaired = secureRuntimeSettings(unsafe)

        assertTrue(repaired.localProxyUsers.single().password.isNotEmpty())
        assertEquals(repaired, requireSafeRuntimeSettings(repaired))
        assertThrows(IllegalArgumentException::class.java) { requireSafeRuntimeSettings(unsafe) }
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeAppliedRuntimeSettings(AppliedRuntimeSettings.from(unsafe))
        }
    }

    @Test
    fun listenAllRepairsEveryEmptyAccountPassword() {
        val repaired = secureRuntimeSettings(
            RuntimeSettings(
                socksListenAll = true,
                localProxyUsers = listOf(LocalProxyUser("alice", ""), LocalProxyUser("bob", "configured")),
            ),
            passwordGenerator = { "generated" },
        )

        assertEquals(listOf(LocalProxyUser("alice", "generated"), LocalProxyUser("bob", "configured")), repaired.localProxyUsers)
        assertEquals(repaired, requireSafeRuntimeSettings(repaired))
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
            localProxyUsers = listOf(LocalProxyUser("persisted-user", "persisted-password")),
        )
        val restoredDraft = persisted.copy(
            mtu = 1360,
            logLevel = "debug",
            localProxyUsers = emptyList(),
        )

        val savedAfterRecreation = hydrateRuntimeSettingsCredentials(restoredDraft, persisted)
            .copy(mtu = 1500)

        assertEquals(1500, savedAfterRecreation.mtu)
        assertEquals("debug", savedAfterRecreation.logLevel)
        assertEquals(persisted.localProxyUsers, savedAfterRecreation.localProxyUsers)
    }

    @Test
    fun lanCredentialHydrationDoesNotGenerateAReplacementPassword() {
        val persisted = RuntimeSettings(
            socksListenAll = true,
            localProxyUsers = listOf(LocalProxyUser("lan-user", "stable-lan-password")),
        )
        val restoredDraft = persisted.copy(
            mtu = 1360,
            localProxyUsers = emptyList(),
        )
        val hydrated = hydrateRuntimeSettingsCredentials(restoredDraft, persisted)

        assertEquals("stable-lan-password", hydrated.localProxyUsers.single().password)
        assertEquals(1360, hydrated.mtu)
    }
}
