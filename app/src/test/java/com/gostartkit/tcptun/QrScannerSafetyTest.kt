package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun qrCameraSelectionPrefersAutofocusStandardBackCamera() {
        val mainCamera = suitability(autoFocus = true, zoom = 1f, systemDefault = true)
        val fixedFocusCamera = suitability(autoFocus = false, zoom = 1f)
        val ultraWideCamera = suitability(autoFocus = true, zoom = 0.5f)
        val telephotoCamera = suitability(autoFocus = true, zoom = 3f)

        assertTrue(compareQrCameraSuitability(mainCamera, fixedFocusCamera) > 0)
        assertTrue(compareQrCameraSuitability(mainCamera, ultraWideCamera) > 0)
        assertTrue(compareQrCameraSuitability(mainCamera, telephotoCamera) > 0)
    }

    @Test
    fun qrCameraSelectionUsesSystemLogicalCameraToBreakEqualSuitability() {
        val logicalDefault = suitability(
            autoFocus = true,
            zoom = 1f,
            systemDefault = true,
            logical = true,
        )
        val otherStandardCamera = suitability(autoFocus = true, zoom = 1f)

        assertTrue(compareQrCameraSuitability(logicalDefault, otherStandardCamera) > 0)
    }

    private fun suitability(
        autoFocus: Boolean,
        zoom: Float,
        systemDefault: Boolean = false,
        logical: Boolean = false,
    ) = QrCameraSuitability(
        supportsAutoFocus = autoFocus,
        intrinsicZoomRatio = zoom,
        isSystemDefault = systemDefault,
        isLogicalCamera = logical,
        hasFlash = false,
    )
}
