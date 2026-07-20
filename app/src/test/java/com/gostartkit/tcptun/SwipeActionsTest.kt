package com.tcptun.client

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeActionsTest {
    @Test
    fun actionsOpenTowardLogicalEnd() {
        assertEquals(-160f, swipeActionsOffset(160f, LayoutDirection.Ltr))
        assertEquals(160f, swipeActionsOffset(160f, LayoutDirection.Rtl))
    }
}
