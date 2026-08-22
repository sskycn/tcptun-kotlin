package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesiredRunningPlanMigrationAndroidTest {
    @Test
    fun v1V2AndV3PlaintextPlansMigrateToEncryptedV4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(DesiredRunningPlanStore.RuntimePreferences, 0)
        val encryptedPreferences = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
        val original = DesiredRunningPlanStore.read(context)

        try {
            for (version in 1..3) {
                DesiredRunningPlanStore.clear(context)
                val marker = "LEGACY_RESTORE_SECRET_V$version"
                val profile = AppConfig(
                    id = "legacy-restore-$version",
                    serverHost = "legacy-$version.example.com",
                    token = marker,
                )
                val plan = ProfileRunPlan(listOf(profile), setOf(profile.id)).normalized()
                val editor = preferences.edit()
                    .putBoolean(DesiredRunningPlanStore.KeyDesiredRunning, true)
                    .putInt(DesiredRunningPlanStore.KeyConfigVersion, version)
                if (version == 1) {
                    editor.putString(DesiredRunningPlanStore.KeyLegacyConfig, profile.toJson().toString())
                    editor.putString(DesiredRunningPlanStore.KeyProfilePlan, "unused-$marker")
                } else {
                    editor.putString(DesiredRunningPlanStore.KeyProfilePlan, DesiredRunningPlanStore.encode(plan))
                    editor.putString(DesiredRunningPlanStore.KeyLegacyConfig, "unused-$marker")
                }
                assertTrue(editor.commit())

                assertEquals(plan, DesiredRunningPlanStore.read(context))

                val secretsId = preferences.getString(DesiredRunningPlanStore.KeySecretsId, null)!!
                assertEquals(4, preferences.getInt(DesiredRunningPlanStore.KeyConfigVersion, 0))
                assertFalse(preferences.contains(DesiredRunningPlanStore.KeyProfilePlan))
                assertFalse(preferences.contains(DesiredRunningPlanStore.KeyLegacyConfig))
                assertFalse(encryptedPreferences.getString(secretsId, "").orEmpty().contains(marker))
            }
        } finally {
            restoreDesiredPlan(context, original)
        }
    }
}
