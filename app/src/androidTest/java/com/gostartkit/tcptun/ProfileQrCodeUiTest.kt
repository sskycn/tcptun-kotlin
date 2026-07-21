package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileQrCodeUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var originalState: ProfilesState

    @Before
    fun rememberOriginalState() {
        originalState = ProfileStore.load(composeRule.activity)
    }

    @After
    fun restoreState() {
        ProfileStore.save(composeRule.activity, originalState)
    }

    @Test
    fun showQrCodeDialogForRawProfileWithCustomPath() {
        val profile = AppConfig(
            id = "qr-custom-raw-path",
            name = "QR Custom Path",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            path = "/",
            tunnelSecurity = "reality",
            sni = "example.com",
            realityPublicKey = "BKZcJpZLNtpVnJcQ7kj6_y2IySMqgYlyjKq-M2OW_yY",
            realityShortId = "a65f93c1dbc5d54a",
            realityFingerprint = "chrome",
            realitySpiderX = "/",
            mux = true,
        )
        ProfileStore.save(composeRule.activity, ProfilesState(profiles = listOf(profile)))
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.show_qr_code))
            .performClick()

        // Dialog opened without crashing; profile name also appears in the list row.
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profile_qr_code))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.profile_qr_code_description, profile.name),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.close)).performClick()
    }

    @Test
    fun unrepresentableProfileShowsQrFailureDialog() {
        val profile = AppConfig(
            id = "qr-unrepresentable-profile",
            name = "QR Mixed Upstream",
            serverHost = "edge.example.com",
            serverPort = "443",
            protocol = "native",
            transport = "raw",
            token = "secret",
            upstreamProtocol = "mixed",
        )
        ProfileStore.save(composeRule.activity, ProfilesState(profiles = listOf(profile)))
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.show_qr_code))
            .performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profile_qr_code_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.close)).performClick()
    }
}
