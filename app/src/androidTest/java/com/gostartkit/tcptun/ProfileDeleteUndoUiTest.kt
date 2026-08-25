package com.tcptun.client

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(profile.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(profile.name).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.delete)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.undo))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val undo = composeRule.onNodeWithText(composeRule.activity.getString(R.string.undo))
        undo.assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(7_000)
        undo.assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            ProfileStore.load(composeRule.activity).profiles.any { it.id == profile.id }
        }
        composeRule.onNodeWithText(profile.name).assertIsDisplayed()
    }

    @Test
    fun dragHandleReordersAndPersistsProfiles() {
        val first = AppConfig(id = "drag-profile-first", name = "First proxy", serverHost = "192.0.2.1")
        val second = AppConfig(id = "drag-profile-second", name = "Second proxy", serverHost = "192.0.2.2")
        ProfileStore.save(composeRule.activity, ProfilesState(profiles = listOf(first, second)))
        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(first.name).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(second.name).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithContentDescription(
                    composeRule.activity.getString(R.string.reorder_profile),
                ).fetchSemanticsNodes().size >= 2
        }
        composeRule.waitForIdle()
        val handles = composeRule.onAllNodesWithContentDescription(
            composeRule.activity.getString(R.string.reorder_profile),
        )
        val firstCenter = handles[0].fetchSemanticsNode().boundsInRoot.center
        val secondCenter = handles[1].fetchSemanticsNode().boundsInRoot.center
        handles[0].performTouchInput {
            val rowDistance = secondCenter.y - firstCenter.y
            swipe(
                start = center,
                end = center.copy(y = center.y + rowDistance * 1.5f),
                durationMillis = 1_000,
            )
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            ProfileStore.load(composeRule.activity).profiles.map { it.id } == listOf(second.id, first.id)
        }
    }
}
