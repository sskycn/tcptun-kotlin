package com.tcptun.client

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
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
            composeRule.onNodeWithContentDescription(activity.getString(R.string.actions)).performClick()
            composeRule.onNodeWithText(activity.getString(R.string.scan_qr_code)).performClick()
            composeRule.onNodeWithText(activity.getString(R.string.scan_profile_qr_code)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()
            composeRule.onNodeWithText(activity.getString(R.string.profiles_title)).assertIsDisplayed()
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.profiles_title)).assertIsDisplayed()
    }
}
