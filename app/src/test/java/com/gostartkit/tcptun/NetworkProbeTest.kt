package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class NetworkProbeTest {
    @Test
    fun boundedAsciiLineHandlesCrLfAndPartialEof() {
        assertEquals(
            "HTTP/1.1 204 No Content",
            ByteArrayInputStream("HTTP/1.1 204 No Content\r\nnext".toByteArray())
                .readBoundedAsciiLine(128),
        )
        assertEquals(
            "partial",
            ByteArrayInputStream("partial".toByteArray()).readBoundedAsciiLine(128),
        )
        assertNull(ByteArrayInputStream(byteArrayOf()).readBoundedAsciiLine(128))
    }

    @Test
    fun boundedAsciiLineRejectsUnboundedHttpStatus() {
        assertThrows(IllegalStateException::class.java) {
            ByteArrayInputStream("12345\n".toByteArray()).readBoundedAsciiLine(4)
        }
    }
}
