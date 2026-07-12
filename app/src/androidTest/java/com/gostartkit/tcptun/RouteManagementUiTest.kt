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
class RouteManagementUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensStructuredRouteRuleEditorFromTopBar() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.bluetooth_receive)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.route_management)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_management)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(activity.getString(R.string.add_route_rule)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_type)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_value)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_outbound)).assertIsDisplayed()
    }
}
