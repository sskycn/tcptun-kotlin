package com.tcptun.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredPublicationGateTest {
    @Test
    fun invalidatedFutureCannotConsumeOrClearItsReplacement() {
        val gate = DeferredPublicationGate()
        val stale = requireNotNull(gate.schedule())
        gate.invalidateScheduled()
        val replacement = requireNotNull(gate.schedule())

        assertFalse(gate.consumeScheduled(stale))
        assertNull(gate.schedule())
        assertTrue(gate.consumeScheduled(replacement))
        assertNotNull(gate.schedule())
    }
}
