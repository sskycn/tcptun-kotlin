package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalProxyAccountsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var originalSettings: RuntimeSettings

    @Before
    fun installSingleAccountSettings() {
        val activity = composeRule.activity
        originalSettings = RuntimeSettingsRepository.read(activity).requireAuthoritativeSettings()
        RuntimeSettingsRepository.write(
            activity,
            originalSettings.copy(
                socksListenAll = false,
                localProxyUsers = listOf(LocalProxyUser("alice", "ui-test-secret-a")),
            ),
        )
    }

    @After
    fun restoreSettings() {
        RuntimeSettingsRepository.write(composeRule.activity, originalSettings)
    }

    @Test
    fun settingsNavigatesToAccountsAndRefreshesCountAfterAdd() {
        val activity = composeRule.activity
        openSettings()

        composeRule.onNodeWithText(activity.resources.getQuantityString(R.plurals.proxy_accounts_count, 1, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.socks_password)).assertDoesNotExist()

        composeRule.onNodeWithText(activity.getString(R.string.proxy_accounts)).performClick()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.add_account)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).performTextInput("bob")
        composeRule.onNodeWithText(activity.getString(R.string.save)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            RuntimeSettingsRepository.read(activity).requireAuthoritativeSettings().localProxyUsers.size == 2
        }
        composeRule.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                activity.resources.getQuantityString(R.plurals.proxy_accounts_count, 2, 2),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSettings() {
        val activity = composeRule.activity
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(activity.getString(R.string.more_options))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.settings)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(activity.getString(R.string.proxy_accounts))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
