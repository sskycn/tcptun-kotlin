package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDeleteUndoUiTest {
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
    }

    @Test
    fun undoRestoresDeletedProfile() {
        val profile = AppConfig(
            id = "delete-undo-ui",
            name = "Delete Undo Link",
            serverHost = "127.0.0.1",
        )
        ProfileStore.save(composeRule.activity, ProfilesState(profiles = listOf(profile)))
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText(profile.name).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.delete)).performClick()
        val undo = composeRule.onNodeWithText(composeRule.activity.getString(R.string.undo))
        undo.assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(7_000)
        undo.assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            ProfileStore.load(composeRule.activity).profiles.any { it.id == profile.id }
        }
        composeRule.onNodeWithText(profile.name).assertIsDisplayed()
    }
}
