package com.tcptun.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnCommandIntentSecurityTest {
    @Test
    fun startIntentContainsOnlyOpaqueCommandReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = RuntimeSettingsRepository.read(context).requireAuthoritativeSettings()
        val password = "intent-secret-password-marker"
        val profile = AppConfig(
            id = "intent-command-profile",
            serverHost = "intent-secret-host.example",
            serverPort = "443",
            token = "intent-secret-token-marker",
        )
        try {
            RuntimeSettingsRepository.write(
                context,
                original.copy(socksListenAll = true, socksPassword = password),
            )

            val intent = VpnServiceIntents.start(context, profile)
            val extras = requireNotNull(intent.extras)

            assertEquals(
                setOf(VpnServiceIntents.ExtraCommandId, VpnServiceIntents.ExtraCommandVersion),
                extras.keySet(),
            )
            val extrasText = "${VpnServiceIntents.ExtraCommandId}=" +
                extras.getString(VpnServiceIntents.ExtraCommandId) +
                ",${VpnServiceIntents.ExtraCommandVersion}=" +
                extras.getInt(VpnServiceIntents.ExtraCommandVersion)
            listOf(profile.token, profile.serverHost, password, "outbounds")
                .forEach { marker -> assertFalse(extrasText.contains(marker)) }
            assertEquals(VpnServiceIntents.CommandVersion, intent.getIntExtra(VpnServiceIntents.ExtraCommandVersion, 0))
            assertTrue(intent.getStringExtra(VpnServiceIntents.ExtraCommandId).orEmpty().isNotBlank())
            val parsed = VpnServiceIntents.parseStartCommand(context, intent)
            assertEquals(profile.token, parsed.plan.profiles.single().token)
            assertThrows(IllegalStateException::class.java) {
                VpnServiceIntents.parseStartCommand(context, intent)
            }
        } finally {
            RuntimeSettingsRepository.write(context, original)
        }
    }

    @Test
    fun updateOutboundsIntentContainsNoProfileSecretsAndIsOneTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = AppConfig(
            id = "update-command-profile",
            serverHost = "update-secret-host.example",
            serverPort = "443",
            protocol = "native",
            token = "update-secret-token-marker",
        )
        val plan = ProfileRunPlan(listOf(profile), setOf(profile.id))

        val intent = VpnServiceIntents.updateOutbounds(context, plan)
        val extras = requireNotNull(intent.extras)

        assertEquals(
            setOf(VpnServiceIntents.ExtraCommandId, VpnServiceIntents.ExtraCommandVersion),
            extras.keySet(),
        )
        val extrasText = "${VpnServiceIntents.ExtraCommandId}=" +
            extras.getString(VpnServiceIntents.ExtraCommandId) +
            ",${VpnServiceIntents.ExtraCommandVersion}=" +
            extras.getInt(VpnServiceIntents.ExtraCommandVersion)
        assertFalse(extrasText.contains(profile.token))
        assertFalse(extrasText.contains(profile.serverHost))
        assertEquals(plan, VpnServiceIntents.parseOutboundsUpdate(context, intent))
        assertThrows(IllegalStateException::class.java) {
            VpnServiceIntents.parseOutboundsUpdate(context, intent)
        }
    }

    @Test
    fun missingCommandReferenceFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(context, TcptunVpnService::class.java)
            .setAction(VpnServiceIntents.ActionStart)
            .putExtra(VpnServiceIntents.ExtraCommandVersion, VpnServiceIntents.CommandVersion)

        assertThrows(IllegalStateException::class.java) {
            VpnServiceIntents.parseStartCommand(context, intent)
        }
        assertTrue(intent.getStringExtra(VpnServiceIntents.ExtraCommandId) == null)
    }
}
