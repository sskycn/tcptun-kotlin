package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tcptun.client.ui.theme.TcpTunTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnDisclosureUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var originalConsentVersion = 0

    @Before
    fun preserveConsent() {
        val preferences = context.getSharedPreferences(VpnDisclosurePreferences, 0)
        originalConsentVersion = preferences.getInt(VpnDisclosureConsentKey, 0)
        preferences.edit().remove(VpnDisclosureConsentKey).commit()
    }

    @After
    fun restoreConsent() {
        context.getSharedPreferences(VpnDisclosurePreferences, 0)
            .edit()
            .putInt(VpnDisclosureConsentKey, originalConsentVersion)
            .commit()
    }

    @Test
    fun consentIsVersionedAndPersisted() {
        assertFalse(hasAcceptedCurrentVpnDisclosure(context))
        assertTrue(acceptCurrentVpnDisclosure(context))
        assertTrue(hasAcceptedCurrentVpnDisclosure(context))

        context.getSharedPreferences(VpnDisclosurePreferences, 0)
            .edit()
            .putInt(VpnDisclosureConsentKey, CurrentVpnDisclosureVersion + 1)
            .commit()
        assertFalse(hasAcceptedCurrentVpnDisclosure(context))
    }

    @Test
    fun disclosureRequiresAnExplicitChoice() {
        var accepted = false
        var declined = false
        composeRule.setContent {
            TcpTunTheme {
                VpnDisclosureDialog(
                    onAccept = { accepted = true },
                    onDecline = { declined = true },
                    onOpenPrivacyPolicy = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.vpn_disclosure_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.privacy_policy))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.vpn_disclosure_accept))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.vpn_disclosure_decline))
            .performClick()

        composeRule.runOnIdle {
            assertFalse(accepted)
            assertTrue(declined)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class VpnDisclosureLaunchFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var originalProfiles: ProfilesState
    private var originalConsentVersion = 0

    @Before
    fun prepareProfileWithoutConsent() {
        val context = composeRule.activity
        originalProfiles = ProfileStore.load(context)
        val preferences = context.getSharedPreferences(VpnDisclosurePreferences, 0)
        originalConsentVersion = preferences.getInt(VpnDisclosureConsentKey, 0)
        preferences.edit().remove(VpnDisclosureConsentKey).commit()
        ProfileStore.save(
            context,
            ProfilesState(
                profiles = listOf(
                    AppConfig(
                        id = "vpn-disclosure-launch-flow",
                        name = "VPN disclosure test profile",
                        serverHost = "192.0.2.1",
                        token = "test-only-token",
                    ),
                ),
            ),
        ).getOrThrow()
        composeRule.activityRule.scenario.recreate()
    }

    @After
    fun restoreProfileAndConsent() {
        ProfileStore.save(composeRule.activity, originalProfiles).getOrThrow()
        composeRule.activity.getSharedPreferences(VpnDisclosurePreferences, 0)
            .edit()
            .putInt(VpnDisclosureConsentKey, originalConsentVersion)
            .commit()
    }

    @Test
    fun decliningDisclosureCancelsThePendingVpnStart() {
        val profileName = "VPN disclosure test profile"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(profileName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(profileName).performClick()

        val title = composeRule.activity.getString(R.string.vpn_disclosure_title)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        assertFalse(hasAcceptedCurrentVpnDisclosure(composeRule.activity))

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.vpn_disclosure_decline),
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ProfileStore.load(composeRule.activity).activeIds.isEmpty()
        }
        assertFalse(hasAcceptedCurrentVpnDisclosure(composeRule.activity))
    }
}
