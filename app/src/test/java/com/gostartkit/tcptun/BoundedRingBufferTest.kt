package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedRingBufferTest {
    @Test
    fun retainsCapacityInFifoOrderAndCountsDrops() {
        val buffer = BoundedRingBuffer<Int>(256)

        repeat(1_000, buffer::append)

        assertEquals((744 until 1_000).toList(), buffer.snapshot())
        assertEquals(744L, buffer.droppedCount)
    }

    @Test
    fun clearResetsContentsAndDroppedCount() {
        val buffer = BoundedRingBuffer<Int>(2)
        buffer.append(1)
        buffer.append(2)
        buffer.append(3)

        buffer.clear()

        assertEquals(emptyList<Int>(), buffer.snapshot())
        assertEquals(0L, buffer.droppedCount)
    }
}
