package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiVisibilityLifecycleTest {
    @Test
    fun overlappingActivitiesRemainVisibleUntilEveryLeaseIsReleased() {
        val tracker = UiVisibilityTracker()
        val firstActivity = tracker.acquire()
        val replacementActivity = tracker.acquire()

        firstActivity.close()

        assertTrue(tracker.isVisible)

        replacementActivity.close()
        assertFalse(tracker.isVisible)
    }

    @Test
    fun activityStopAndDestroyCanReleaseTheSameLeaseSafely() {
        val tracker = UiVisibilityTracker()
        val activity = tracker.acquire()

        activity.close()
        activity.close()

        assertFalse(tracker.isVisible)
    }
}
