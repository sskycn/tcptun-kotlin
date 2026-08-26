package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativeObservationCallbackSlotTest {
    @Test
    fun desiredCallbackSurvivesHiddenSuspensionAndRestoresWithoutRestart() {
        var allowed = true
        val installs = mutableListOf<Any?>()
        val slot = NativeObservationCallbackSlot(
            allowed = { allowed },
            installNative = installs::add,
        )
        val callback = Any()

        slot.set(callback)
        allowed = false
        slot.reconcile()
        allowed = true
        slot.reconcile()

        assertEquals(3, installs.size)
        assertSame(callback, installs[0])
        assertNull(installs[1])
        assertSame(callback, installs[2])
        assertSame(callback, slot.desired)
        assertSame(callback, slot.installed)
    }

    @Test
    fun failedNativeTransitionKeepsDesiredAndLastConfirmedInstalledState() {
        var allowed = true
        var failNext = false
        val callback = Any()
        val slot = NativeObservationCallbackSlot(
            allowed = { allowed },
            installNative = {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("native setter failed")
                }
            },
        )
        slot.set(callback)
        allowed = false
        failNext = true

        runCatching(slot::reconcile)

        assertSame(callback, slot.desired)
        assertSame(callback, slot.installed)
        slot.reconcile()
        assertNull(slot.installed)
    }

    @Test
    fun releaseAfterCloseDropsBothStrongReferences() {
        val callback = Any()
        val slot = NativeObservationCallbackSlot(allowed = { true }, installNative = {})
        slot.set(callback)

        slot.releaseAfterNativeClose()

        assertNull(slot.desired)
        assertNull(slot.installed)
    }

    @Test
    fun rapidVisibilityTogglesAlwaysConvergeToDesiredCallback() {
        var allowed = true
        val callback = Any()
        val installs = mutableListOf<Any?>()
        val slot = NativeObservationCallbackSlot({ allowed }, installs::add)
        slot.set(callback)

        repeat(100) {
            allowed = false
            slot.reconcile()
            allowed = true
            slot.reconcile()
        }

        assertSame(callback, slot.desired)
        assertSame(callback, slot.installed)
        assertEquals(201, installs.size)
    }
}
