package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpingProgressTest {
    @Test
    fun averageUsesSuccessfulLinksAndKeepsEveryStep() {
        val progress = TcpingProgress(
            total = 3,
            results = listOf(
                TcpingLinkResult("A", elapsedMs = 12),
                TcpingLinkResult("B", error = "timeout"),
                TcpingLinkResult("C", elapsedMs = 20),
            ),
        )

        assertEquals(16L, progress.averageMs)
        assertEquals(listOf("A", "B", "C"), progress.results.map(TcpingLinkResult::profileName))
    }

    @Test
    fun staleStepsCannotOverwriteANewerTcpingRequest() {
        val first = TcptunState.beginTcping("Google", 2)
        val second = TcptunState.beginTcping("GitHub", 1)

        TcptunState.beginTcpingStep(first, 1, 2, "stale")
        TcptunState.completeTcpingStep(first, TcpingLinkResult("stale", elapsedMs = 1))
        TcptunState.beginTcpingStep(second, 1, 1, "current")
        TcptunState.completeTcpingStep(second, TcpingLinkResult("current", elapsedMs = 9))
        TcptunState.finishTcping(second)

        val progress = TcptunState.state.value.tcping
        assertEquals(second, progress.requestId)
        assertEquals("GitHub", progress.targetLabel)
        assertEquals(listOf("current"), progress.results.map(TcpingLinkResult::profileName))
        assertEquals(9L, progress.averageMs)
        assertFalse(progress.running)
        assertTrue(progress.error.isEmpty())
    }
}
