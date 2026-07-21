package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrScannerSafetyTest {
    @Test
    fun zoomSuggestionIsClampedToCameraRange() {
        assertEquals(1f, safeCameraZoomRatio(0.5f, 1f, 8f))
        assertEquals(4f, safeCameraZoomRatio(4f, 1f, 8f))
        assertEquals(8f, safeCameraZoomRatio(10f, 1f, 8f))
    }

    @Test
    fun invalidCameraZoomStateIsRejectedWithoutThrowing() {
        assertNull(safeCameraZoomRatio(Float.NaN, 1f, 8f))
        assertNull(safeCameraZoomRatio(2f, Float.NaN, 8f))
        assertNull(safeCameraZoomRatio(2f, 8f, 1f))
    }
}
