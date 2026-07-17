package com.tcptun.client

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardImportUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var originalState: ProfilesState
    private var originalClipboardText: String? = null

    @Before
    fun rememberOriginalState() {
        val activity = composeRule.activity
        originalState = ProfileStore.load(activity)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        originalClipboardText = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(activity)
            ?.toString()
    }

    @After
    fun restoreState() {
        val activity = composeRule.activity
        ProfileStore.save(activity, originalState)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        originalClipboardText?.let { value ->
            clipboard?.setPrimaryClip(ClipData.newPlainText("restored", value))
        } ?: clipboard?.clearPrimaryClip()
    }

    @Test
    fun importsCurrentSchemaJsonFromClipboard() {
        val activity = composeRule.activity
        val clipboardConfig = """
            {
              "inbounds": [{
                "tag": "local-mixed",
                "type": "mixed",
                "address": ["127.0.0.1:1080"],
                "network": ["tcp", "udp"]
              }],
              "outbounds": [{
                "tag": "native",
                "type": "native",
                "address": ["[::1]:443"],
                "token": "clipboard-ui-test-credential",
                "network": ["tcp", "udp"],
                "transport": {"type": "raw"},
                "security": {
                  "type": "reality",
                  "server_name": "example.com",
                  "fingerprint": "chrome",
                  "public_key": "3HNAKQ6cNuB2YDXVmwtMRLKpfGhBnykI2rXDmW9CKT4",
                  "short_id": "00",
                  "spider_x": "/"
                },
                "mux": {}
              }],
              "route": {"default_outbound": "native", "rules": []}
            }
        """.trimIndent()

        ProfileStore.save(activity, ProfilesState(profiles = emptyList()))
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("tcptun test config", clipboardConfig))
        composeRule.activityRule.scenario.recreate()

        val currentActivity = composeRule.activity
        composeRule.onNodeWithContentDescription(currentActivity.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(currentActivity.getString(R.string.import_from_clipboard)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            ProfileStore.load(currentActivity).profiles.any { it.rawConfigJson.isNotBlank() }
        }
        composeRule.onNodeWithText("tcptun-json").assertIsDisplayed()
        val remainingText = currentActivity.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(currentActivity)
            ?.toString()
        assertFalse(remainingText == clipboardConfig)
    }
}
