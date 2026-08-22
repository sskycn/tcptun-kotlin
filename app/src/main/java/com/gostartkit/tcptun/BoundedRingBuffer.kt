package com.tcptun.client

/** Fixed-capacity FIFO with O(1) append and allocation only when a snapshot is requested. */
internal class BoundedRingBuffer<T>(val capacity: Int) {
    private val elements: Array<Any?>
    private var start = 0
    private var size = 0

    var droppedCount: Long = 0
        private set

    init {
        require(capacity > 0) { "ring buffer capacity must be positive" }
        elements = arrayOfNulls(capacity)
    }

    fun append(value: T) {
        if (size < capacity) {
            elements[(start + size) % capacity] = value
            size += 1
        } else {
            elements[start] = value
            start = (start + 1) % capacity
            droppedCount += 1
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun snapshot(): List<T> = List(size) { index ->
        elements[(start + index) % capacity] as T
    }

    fun clear() {
        elements.fill(null)
        start = 0
        size = 0
        droppedCount = 0
    }
}
