package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowAnalysisUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensFlowAnalysisFromTopBar() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis_current)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis_events)).assertIsDisplayed()
    }

    @Test
    fun settingsExposeSingleAppAnalysisSelector() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.settings)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis_app)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.flow_analysis_note)).assertIsDisplayed()
    }
}
