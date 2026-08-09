package com.tcptun.client

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitReasonTest {
    @Test
    fun labelsProcessLevelVpnExitCauses() {
        assertEquals("Java crash", processExitReasonLabel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("native crash", processExitReasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("low memory", processExitReasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("ANR", processExitReasonLabel(ApplicationExitInfo.REASON_ANR))
    }
}
