package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretAtRestAndroidTest {
    @Test
    fun noCredentialMarkerAppearsInAnyApplicationPreferenceValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalProfiles = ProfileStore.load(context)
        val originalRuntime = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        val originalDesired = DesiredRunningPlanStore.read(context)
        val markers = listOf(
            "GLOBAL_TOKEN_3a30c1",
            "GLOBAL_RAW_CONFIG_98ebd2",
            "GLOBAL_REALITY_4dd810",
            "GLOBAL_ECH_a1418f",
            "GLOBAL_SOCKS_USER_25cf74",
            "GLOBAL_SOCKS_PASSWORD_c0b4ee",
        )
        val profile = AppConfig(
            id = "global-secret-at-rest-test",
            serverHost = "global-secret-test.example.com",
            token = markers[0],
            rawConfigJson = "{\"credential\":\"${markers[1]}\"}",
            realityPublicKey = markers[2],
            echPublicKey = markers[3],
        )
        val runningProfile = profile.copy(
            rawConfigJson = "",
            realityPublicKey = "",
            echPublicKey = "",
        )
        val plan = ProfileRunPlan(listOf(runningProfile), setOf(runningProfile.id)).normalized()

        try {
            ProfileStore.save(context, ProfilesState(listOf(profile), setOf(profile.id))).getOrThrow()
            RuntimeSettingsRepository.write(
                context,
                originalRuntime.copy(
                    socksListenAll = false,
                    socksUsername = markers[4],
                    socksPassword = markers[5],
                ),
            )
            DesiredRunningPlanStore.publish(context, DesiredRunningPlanStore.encode(plan))

            listOf("tcptun", "tcptun_runtime", "tcptun_encrypted_secrets").forEach { name ->
                val persistedText = context.getSharedPreferences(name, 0).all.values
                    .flatMap(::preferenceStrings)
                markers.forEach { marker ->
                    assertFalse("$name contains plaintext marker $marker", persistedText.any { marker in it })
                }
            }
        } finally {
            ProfileStore.save(context, originalProfiles).getOrThrow()
            RuntimeSettingsRepository.write(context, originalRuntime)
            restoreDesiredPlan(context, originalDesired)
        }
    }

    private fun preferenceStrings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Set<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
}
