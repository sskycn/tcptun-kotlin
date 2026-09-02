package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSettingsRecreationAndroidTest {
    @Test
    fun savedStateAlwaysRestoresFullTunnel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        try {
            RuntimeSettingsRepository.write(context, original.copy(vpnRoutePlan = AndroidVpnRoutePlan.FullTunnel))
            val reloaded = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
            assertEquals(AndroidVpnRoutePlan.FullTunnel, reloaded.vpnRoutePlan)

            val legacySavedState = """{"mtu":1400,"logLevel":"info"}"""
            assertEquals(
                AndroidVpnRoutePlan.FullTunnel,
                requireNotNull(decodeRuntimeSettingsSavedState(legacySavedState)).vpnRoutePlan,
            )
        } finally {
            RuntimeSettingsRepository.write(context, original)
        }
    }

    @Test
    fun restoredDraftRehydratesCredentialsBeforeUnrelatedPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        val persisted = original.copy(
            mtu = 1400,
            logLevel = "info",
            socksListenAll = false,
            localProxyUsers = listOf(LocalProxyUser("recreation-user-marker", "recreation-password-marker")),
        )
        try {
            RuntimeSettingsRepository.write(context, persisted)
            val savedState = encodeRuntimeSettingsSavedState(persisted.copy(mtu = 1360, logLevel = "debug"))
            assertFalse(savedState.contains(persisted.localProxyUsers.single().username))
            assertFalse(savedState.contains(persisted.localProxyUsers.single().password))
            val restoredDraft = requireNotNull(decodeRuntimeSettingsSavedState(savedState))
            assertEquals(emptyList<LocalProxyUser>(), restoredDraft.localProxyUsers)
            assertEquals(AndroidVpnRoutePlan.FullTunnel, restoredDraft.vpnRoutePlan)

            val hydrated = hydrateRuntimeSettingsCredentials(
                restoredDraft,
                RuntimeSettingsRepository.read(context).requireAuthoritativeSettings(),
            )
            RuntimeSettingsRepository.write(context, hydrated.copy(mtu = 1500))
            val reloaded = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()

            assertEquals(1500, reloaded.mtu)
            assertEquals("debug", reloaded.logLevel)
            assertEquals(persisted.localProxyUsers, reloaded.localProxyUsers)
            assertEquals(AndroidVpnRoutePlan.FullTunnel, reloaded.vpnRoutePlan)
        } finally {
            RuntimeSettingsRepository.write(context, original)
        }
    }

    @Test
    fun lanPasswordRemainsStableAcrossRecreationAndUnrelatedChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        val persisted = original.copy(
            mtu = 1400,
            localProxyProtocol = "mixed",
            socksListenAll = true,
            localProxyUsers = listOf(LocalProxyUser("lan-recreation-user", "stable-lan-recreation-password")),
        )
        try {
            RuntimeSettingsRepository.write(context, persisted)
            val restoredDraft = requireNotNull(
                decodeRuntimeSettingsSavedState(encodeRuntimeSettingsSavedState(persisted.copy(mtu = 1360))),
            )
            val hydrated = hydrateRuntimeSettingsCredentials(
                restoredDraft,
                RuntimeSettingsRepository.read(context).requireAuthoritativeSettings(),
            )
            RuntimeSettingsRepository.write(context, hydrated.copy(mtu = 1500))
            val reloaded = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()

            assertEquals(1500, reloaded.mtu)
            assertEquals("mixed", reloaded.localProxyProtocol)
            assertEquals(persisted.localProxyUsers, reloaded.localProxyUsers)
            assertEquals(AndroidVpnRoutePlan.FullTunnel, reloaded.vpnRoutePlan)
        } finally {
            RuntimeSettingsRepository.write(context, original)
        }
    }
}
