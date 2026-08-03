package com.tcptun.client

import android.Manifest
import android.os.ParcelFileDescriptor
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class QrScannerLifecycleUiTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val cameraPermissionRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                grantCameraPermission()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(cameraPermissionRule).around(composeRule)

    private fun grantCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val output = instrumentation.uiAutomation.executeShellCommand(
            "pm grant $packageName ${Manifest.permission.CAMERA}",
        )
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    @Test
    fun scannerCanBindAndDisposeRepeatedlyAcrossActivityRecreation() {
        val activity = composeRule.activity
        repeat(3) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithContentDescription(activity.getString(R.string.more_options))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
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
