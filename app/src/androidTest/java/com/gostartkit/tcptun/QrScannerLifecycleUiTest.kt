package com.tcptun.client

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrScannerLifecycleUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${composeRule.activity.packageName} ${Manifest.permission.CAMERA}",
        ).close()
    }

    @Test
    fun scannerCanBindAndDisposeRepeatedlyAcrossActivityRecreation() {
        val activity = composeRule.activity
        repeat(3) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription(activity.getString(R.string.actions))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(activity.getString(R.string.actions)).performClick()
            composeRule.onNodeWithText(activity.getString(R.string.scan_qr_code)).performClick()
            composeRule.onNodeWithText(activity.getString(R.string.scan_profile_qr_code)).assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag(QrCameraReadyTestTag).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(activity.getString(R.string.profiles_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.profiles_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profiles_title)).assertIsDisplayed()
    }
}
