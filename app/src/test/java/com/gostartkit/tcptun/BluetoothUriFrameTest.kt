package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

class BluetoothUriFrameTest {
    @Test
    fun frameRoundTripsProfileUri() {
        val uri = "vless://token@example.com:443?security=reality#example"
        val frame = BluetoothUriFrame.encode("0123", uri)

        assertEquals(uri, BluetoothUriFrame.decode("0123", frame))
        assertFalse(String(frame).contains(uri))
    }

    @Test
    fun encryptedFrameCanBeReceivedBeforeCodeIsEntered() {
        val uri = "vless://token@example.com:443?security=reality#receive-first"
        val bytes = BluetoothUriFrame.encode("9071", uri)
        val encryptedFrame = DataInputStream(ByteArrayInputStream(bytes)).use(BluetoothUriFrame::read)

        assertEquals(uri, BluetoothUriFrame.decrypt("9071", encryptedFrame))
        assertThrows(BluetoothCodeMismatchException::class.java) {
            BluetoothUriFrame.decrypt("9072", encryptedFrame)
        }
    }

    @Test
    fun frameRejectsCorruptedPayload() {
        val frame = BluetoothUriFrame.encode("4821", "native://example.com:443#example")
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()

        assertThrows(IOException::class.java) { BluetoothUriFrame.decode("4821", frame) }
    }

    @Test
    fun frameRejectsWrongCode() {
        val frame = BluetoothUriFrame.encode("4821", "native://example.com:443#example")

        assertThrows(BluetoothCodeMismatchException::class.java) {
            BluetoothUriFrame.decode("4822", frame)
        }
    }

    @Test
    fun frameRejectsOversizedUri() {
        val oversized = "x".repeat(BluetoothUriFrame.MaxUriBytes + 1)

        assertThrows(IllegalArgumentException::class.java) { BluetoothUriFrame.encode("1234", oversized) }
    }
}
