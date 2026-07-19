package com.tcptun.client

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteManagementUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var originalRules: List<ManagedRouteRule>

    @Before
    fun rememberOriginalRules() {
        originalRules = RouteRuleStore.load(composeRule.activity)
    }

    @After
    fun restoreOriginalRules() {
        RouteRuleStore.save(composeRule.activity, originalRules).getOrThrow()
    }

    @Test
    fun opensStructuredRouteRuleEditorFromTopBar() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_management)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_management)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(activity.getString(R.string.add_route_rule)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_type)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_value)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.route_rule_outbound)).assertIsDisplayed()
    }

    @Test
    fun dragHandleReordersAndPersistsRules() {
        val first = ManagedRouteRule(id = "drag-first", value = "first.example")
        val second = ManagedRouteRule(id = "drag-second", value = "second.example")
        RouteRuleStore.save(composeRule.activity, listOf(first, second)).getOrThrow()

        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.route_management)).performClick()

        composeRule.onAllNodesWithContentDescription(activity.getString(R.string.reorder_rule))[0]
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, 32f), delayMillis = 100)
                moveBy(Offset(0f, 64f), delayMillis = 100)
                moveBy(Offset(0f, 96f), delayMillis = 100)
                up()
            }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            RouteRuleStore.load(activity).map { it.id } == listOf(second.id, first.id)
        }
    }
}
