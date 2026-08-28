package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
    fun homeMenuNavigatesToAccountsWhileSettingsHasNoAccountEntry() {
        val activity = composeRule.activity
        openSettings()

        composeRule.onNodeWithText(activity.getString(R.string.proxy_accounts)).assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.socks_password)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()

        openProxyAccounts()
        composeRule.onNodeWithText("alice").assertIsDisplayed()
        composeRule.onNodeWithText("••••••••").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(activity.getString(R.string.scan_qr_code)).assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.add_account)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).performTextInput("bob")
        composeRule.onNodeWithText(activity.getString(R.string.save)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            RuntimeSettingsRepository.read(activity).requireAuthoritativeSettings().localProxyUsers.size == 2
        }
        composeRule.onNodeWithContentDescription(activity.getString(R.string.back)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(activity.getString(R.string.profiles_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun accountRowClickOpensEditor() {
        val activity = composeRule.activity
        openProxyAccounts()

        composeRule.onNodeWithTag(localProxyAccountRowTestTag(0)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.edit_account)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).assertIsDisplayed()
    }

    @Test
    fun accountRowExposesShareAndItsOwnQrCodeDirectly() {
        val activity = composeRule.activity
        RuntimeSettingsRepository.write(
            activity,
            originalSettings.copy(
                localProxyUsers = listOf(
                    LocalProxyUser("alice", "ui-test-secret-a"),
                    LocalProxyUser("bob", "ui-test-secret-b"),
                ),
            ),
        )
        openProxyAccounts()

        composeRule.onAllNodesWithContentDescription(activity.getString(R.string.share))[0].performClick()
        composeRule.onNodeWithText(
            activity.getString(R.string.proxy_account_share_confirmation, "alice"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.cancel)).performClick()

        composeRule.onAllNodesWithContentDescription(activity.getString(R.string.show_qr_code))[0].performClick()
        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.proxy_account_qr_code_description, "alice"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.copy_share_code)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.close)).performClick()

        composeRule.onAllNodesWithContentDescription(activity.getString(R.string.show_qr_code))[1].performClick()
        composeRule.onNodeWithContentDescription(
            activity.getString(R.string.proxy_account_qr_code_description, "bob"),
        ).assertIsDisplayed()
    }

    @Test
    fun swipeEditOpensAccountEditor() {
        val activity = composeRule.activity
        openProxyAccounts()

        composeRule.onNodeWithTag(localProxyAccountRowTestTag(0)).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(activity.getString(R.string.edit)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.edit_account)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.socks_username)).assertIsDisplayed()
    }

    @Test
    fun swipeDeleteShowsConfirmation() {
        val activity = composeRule.activity
        openProxyAccounts()

        composeRule.onNodeWithTag(localProxyAccountRowTestTag(0)).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(activity.getString(R.string.delete)).performClick()
        composeRule.onNodeWithText(
            activity.getString(R.string.delete_proxy_account_confirmation, "alice"),
        ).assertIsDisplayed()
    }

    @Test
    fun swipeDeleteCannotRemoveLastListenAllAccount() {
        val activity = composeRule.activity
        RuntimeSettingsRepository.write(
            activity,
            originalSettings.copy(
                socksListenAll = true,
                localProxyUsers = listOf(LocalProxyUser("alice", "ui-test-secret-a")),
            ),
        )
        openProxyAccounts()

        composeRule.onNodeWithTag(localProxyAccountRowTestTag(0)).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(activity.getString(R.string.delete)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.proxy_account_required_for_lan)).assertIsDisplayed()
        composeRule.onNodeWithText(
            activity.getString(R.string.delete_proxy_account_confirmation, "alice"),
        ).assertDoesNotExist()
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
            composeRule.onAllNodesWithText(activity.getString(R.string.settings))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openProxyAccounts() {
        val activity = composeRule.activity
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription(activity.getString(R.string.more_options))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(activity.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.proxy_accounts)).assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("alice").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
