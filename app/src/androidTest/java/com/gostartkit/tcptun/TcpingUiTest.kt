package com.tcptun.client

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TcpingUiTest {
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
        TcptunState.clearTcping()
        TcptunState.setStatus("Stopped")
        composeRule.activity.stopService(TcptunVpnService.stopIntent(composeRule.activity))
    }

    @Test
    fun runningVpnWithMultipleActiveProfilesCanStartTcpingFromBottomBar() {
        val first = AppConfig(id = "tcping-ui-a", name = "Link A", serverHost = "127.0.0.1")
        val second = AppConfig(id = "tcping-ui-b", name = "Link B", serverHost = "127.0.0.2")
        ProfileStore.save(
            composeRule.activity,
            ProfilesState(
                profiles = listOf(first, second),
                activeIds = linkedSetOf(first.id, second.id),
            ),
        )
        TcptunState.setStatus("Running")
        TcptunState.setConnectionsReady(true)
        composeRule.activityRule.scenario.recreate()

        val readyText = composeRule.activity.getString(R.string.connected_tap_test)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(readyText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(readyText)
            .assertHasClickAction()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            TcptunState.state.value.tcping.requestId > 0L
        }
    }
}
