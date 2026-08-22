package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesiredRunningPlanStoreEncryptionTest {
    @Test
    fun publishStoresOnlyCiphertextAndClearRemovesPointerAndBlob() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(DesiredRunningPlanStore.RuntimePreferences, 0)
        val encryptedPreferences = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
        val original = DesiredRunningPlanStore.read(context)
        val marker = "RESTORE_PLAN_SECRET_7f21d4"
        val plan = ProfileRunPlan(
            profiles = listOf(
                AppConfig(
                    id = "restore-plan-encryption-test",
                    serverHost = "restore.example.com",
                    token = marker,
                ),
            ),
            activeIds = setOf("restore-plan-encryption-test"),
        ).normalized()

        try {
            DesiredRunningPlanStore.publish(context, DesiredRunningPlanStore.encode(plan))

            val secretsId = preferences.getString(DesiredRunningPlanStore.KeySecretsId, null)!!
            val ciphertext = encryptedPreferences.getString(secretsId, null)!!
            assertEquals(DesiredRunningPlanStore.CurrentConfigVersion,
                preferences.getInt(DesiredRunningPlanStore.KeyConfigVersion, 0))
            assertFalse(preferences.contains(DesiredRunningPlanStore.KeyProfilePlan))
            assertFalse(preferences.contains(DesiredRunningPlanStore.KeyLegacyConfig))
            assertFalse(ciphertext.contains(marker))
            assertEquals(plan, DesiredRunningPlanStore.read(context))

            DesiredRunningPlanStore.clear(context)

            assertFalse(preferences.getBoolean(DesiredRunningPlanStore.KeyDesiredRunning, true))
            assertFalse(preferences.contains(DesiredRunningPlanStore.KeySecretsId))
            assertFalse(encryptedPreferences.contains(secretsId))
        } finally {
            restoreDesiredPlan(context, original)
        }
    }

    @Test
    fun corruptedCiphertextFailsClosedWithoutDeletingAuthoritativeState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(DesiredRunningPlanStore.RuntimePreferences, 0)
        val encryptedPreferences = context.getSharedPreferences("tcptun_encrypted_secrets", 0)
        val original = DesiredRunningPlanStore.read(context)
        val plan = ProfileRunPlan(
            listOf(AppConfig(id = "corrupt-plan-test", serverHost = "corrupt.example.com", token = "secret")),
            setOf("corrupt-plan-test"),
        ).normalized()

        try {
            DesiredRunningPlanStore.publish(context, DesiredRunningPlanStore.encode(plan))
            val secretsId = preferences.getString(DesiredRunningPlanStore.KeySecretsId, null)!!
            val ciphertext = encryptedPreferences.getString(secretsId, null)!!
            val corrupted = ciphertext.dropLast(1) + if (ciphertext.last() == '0') '1' else '0'
            encryptedPreferences.edit().putString(secretsId, corrupted).commit()

            assertNull(DesiredRunningPlanStore.read(context))
            assertTrue(preferences.getBoolean(DesiredRunningPlanStore.KeyDesiredRunning, false))
            assertEquals(secretsId, preferences.getString(DesiredRunningPlanStore.KeySecretsId, null))
            assertTrue(encryptedPreferences.contains(secretsId))

            encryptedPreferences.edit().putString(secretsId, ciphertext).commit()
        } finally {
            restoreDesiredPlan(context, original)
        }
    }
}

internal fun restoreDesiredPlan(context: android.content.Context, plan: ProfileRunPlan?) {
    if (plan == null) {
        DesiredRunningPlanStore.clear(context)
    } else {
        DesiredRunningPlanStore.publish(context, DesiredRunningPlanStore.encode(plan))
    }
}
