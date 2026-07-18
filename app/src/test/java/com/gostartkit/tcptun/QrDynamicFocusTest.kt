package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDynamicFocusTest {
    @Test
    fun locatorOutputMapsNormalizedDetectionToFrameCoordinates() {
        val candidates = parseLocatorOutput(
            rows = listOf(floatArrayOf(0f, 1f, 0.92f, 0.10f, 0.20f, 0.40f, 0.60f)),
            frameWidth = 1000,
            frameHeight = 500,
        )

        assertEquals(1, candidates.size)
        assertEquals(100f, candidates.single().left, 0.001f)
        assertEquals(100f, candidates.single().top, 0.001f)
        assertEquals(400f, candidates.single().right, 0.001f)
        assertEquals(300f, candidates.single().bottom, 0.001f)
    }

    @Test
    fun locatorOutputRejectsBackgroundInvalidAndDegenerateRows() {
        val candidates = parseLocatorOutput(
            rows = listOf(
                floatArrayOf(0f, 0f, 0.99f, 0.1f, 0.1f, 0.4f, 0.4f),
                floatArrayOf(0f, 1f, Float.NaN, 0.1f, 0.1f, 0.4f, 0.4f),
                floatArrayOf(0f, 1f, 0.9f, 0.1f, 0.1f, 0.101f, 0.101f),
            ),
            frameWidth = 1000,
            frameHeight = 500,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun zoomStaysStableWhileTargetIsInUsefulSizeBand() {
        assertEquals(
            2.1f,
            desiredZoomRatio(
                currentZoom = 2.1f,
                observedSizeRatio = 0.42f,
                minZoom = 1f,
                maxZoom = 2.6f,
            ),
            0.001f,
        )
    }

    @Test
    fun zoomUsesCurrentRatioToAvoidOneXZoomFeedbackLoop() {
        val zoomed = desiredZoomRatio(
            currentZoom = 1f,
            observedSizeRatio = 0.20f,
            minZoom = 1f,
            maxZoom = 2.6f,
        )
        assertEquals(2.1f, zoomed, 0.001f)

        assertEquals(
            zoomed,
            desiredZoomRatio(
                currentZoom = zoomed,
                observedSizeRatio = 0.42f,
                minZoom = 1f,
                maxZoom = 2.6f,
            ),
            0.001f,
        )
    }

    @Test
    fun zoomIsCappedForVerySmallTargets() {
        assertEquals(
            2.6f,
            desiredZoomRatio(
                currentZoom = 1f,
                observedSizeRatio = 0.03f,
                minZoom = 1f,
                maxZoom = 2.6f,
            ),
            0.001f,
        )
    }

    @Test
    fun targetSmoothingRejectsSmallDetectionJitterButKeepsTimestampFresh() {
        val previous = target(x = 0.50f, y = 0.50f, size = 0.30f, updatedAt = 10L)
        val observed = target(x = 0.54f, y = 0.46f, size = 0.34f, updatedAt = 20L)

        val smoothed = smoothTarget(previous, observed)

        assertTrue(smoothed.normalizedX in previous.normalizedX..observed.normalizedX)
        assertTrue(smoothed.normalizedY in observed.normalizedY..previous.normalizedY)
        assertTrue(smoothed.sizeRatio in previous.sizeRatio..observed.sizeRatio)
        assertEquals(observed.updatedAtMs, smoothed.updatedAtMs)
    }

    @Test
    fun refocusRequiresMeaningfulPositionOrSizeChange() {
        val previous = target(x = 0.5f, y = 0.5f, size = 0.4f)

        assertFalse(targetMoved(previous, target(x = 0.52f, y = 0.51f, size = 0.42f)))
        assertTrue(targetMoved(previous, target(x = 0.60f, y = 0.5f, size = 0.4f)))
        assertTrue(targetMoved(previous, target(x = 0.5f, y = 0.5f, size = 0.25f)))
    }

    private fun target(
        x: Float,
        y: Float,
        size: Float,
        updatedAt: Long = 0L,
    ) = DynamicFocusTarget(
        normalizedX = x,
        normalizedY = y,
        sizeRatio = size,
        updatedAtMs = updatedAt,
    )
}
