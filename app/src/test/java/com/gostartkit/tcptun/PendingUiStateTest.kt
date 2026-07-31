package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingUiStateTest {
    @Test
    fun boundedEncoderAcceptsLimitAndRejectsOversizedOrFailedEncoding() {
        assertEquals(
            "12345678",
            encodeBoundedSavedState("12345678", maxLength = 8) { it },
        )
        assertNull(encodeBoundedSavedState("123456789", maxLength = 8) { it })
        assertNull(encodeBoundedSavedState("value", maxLength = 8) { error("encode failed") })
        assertNull(encodeBoundedSavedState("", maxLength = 8) { it })
        assertNull(encodeBoundedSavedState(null as String?, maxLength = 8) { it })
    }

    @Test
    fun boundedDecoderAcceptsLimitAndSafelyClearsInvalidState() {
        assertEquals(
            "decoded:12345678",
            decodeBoundedSavedState("12345678", maxLength = 8) { "decoded:$it" },
        )
        assertNull(decodeBoundedSavedState("123456789", maxLength = 8) { it })
        assertNull(decodeBoundedSavedState("malformed", maxLength = 16) { error("parse failed") })
        assertNull(decodeBoundedSavedState("", maxLength = 8) { it })
        assertNull(decodeBoundedSavedState(null, maxLength = 8) { it })
    }
}
