package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsMenuUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensLogsFromTopBarMenu() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.logs)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.running_logs)).assertIsDisplayed()
    }
}
