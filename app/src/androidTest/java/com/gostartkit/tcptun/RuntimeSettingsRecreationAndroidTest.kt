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
    fun restoredDraftRehydratesCredentialsBeforeUnrelatedPersistence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        val persisted = original.copy(
            mtu = 1400,
            logLevel = "info",
            socksListenAll = false,
            socksUsername = "recreation-user-marker",
            socksPassword = "recreation-password-marker",
        )
        try {
            RuntimeSettingsRepository.write(context, persisted)
            val savedState = encodeRuntimeSettingsSavedState(persisted.copy(mtu = 1360, logLevel = "debug"))
            assertFalse(savedState.contains(persisted.socksUsername))
            assertFalse(savedState.contains(persisted.socksPassword))
            val restoredDraft = requireNotNull(decodeRuntimeSettingsSavedState(savedState))
            assertEquals("", restoredDraft.socksUsername)
            assertEquals("", restoredDraft.socksPassword)

            val hydrated = hydrateRuntimeSettingsCredentials(
                restoredDraft,
                RuntimeSettingsRepository.read(context).requireAuthoritativeSettings(),
            )
            RuntimeSettingsRepository.write(context, hydrated.copy(mtu = 1500))
            val reloaded = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()

            assertEquals(1500, reloaded.mtu)
            assertEquals("debug", reloaded.logLevel)
            assertEquals(persisted.socksUsername, reloaded.socksUsername)
            assertEquals(persisted.socksPassword, reloaded.socksPassword)
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
            socksListenAll = true,
            socksUsername = "lan-recreation-user",
            socksPassword = "stable-lan-recreation-password",
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
            assertEquals(persisted.socksUsername, reloaded.socksUsername)
            assertEquals(persisted.socksPassword, reloaded.socksPassword)
        } finally {
            RuntimeSettingsRepository.write(context, original)
        }
    }
}
