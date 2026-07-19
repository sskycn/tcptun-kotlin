package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class TcpingProbeTest {
    @Test
    fun parsesNativeQuicBackoffDurations() {
        assertEquals(
            13_360L,
            transientQuicRetryDelayMillis(IllegalStateException("mux session dial is backing off for native QUIC (13.31s remaining)")),
        )
        assertEquals(
            273L,
            transientQuicRetryDelayMillis(
                IllegalStateException("mux session dial is backing off for native QUIC (223ms remaining)"),
            ),
        )
        assertEquals(
            100L,
            transientQuicRetryDelayMillis(IllegalStateException("close called for canceled stream 0")),
        )
        assertEquals(
            100L,
            transientQuicRetryDelayMillis(
                IllegalStateException(
                    "STREAM_LIMIT_ERROR (local): peer tried to open stream 3 (current limit: -1)",
                ),
            ),
        )
        assertNull(transientQuicRetryDelayMillis(IllegalStateException("connection refused")))
    }

    @Test
    fun waitsForMuxBackoffThenRetriesWithinTheSameTcpingRequest() {
        var now = 1_000L
        val attemptTimeouts = mutableListOf<Long>()
        var attempts = 0

        val elapsed = probeOutboundWithTransientQuicRetry(
            totalTimeoutMillis = 20_000L,
            attemptTimeoutMillis = 3_000L,
            nowMillis = { now },
            pause = { delay -> now += delay },
        ) { timeout ->
            attemptTimeouts += timeout
            attempts += 1
            if (attempts == 1) {
                throw IllegalStateException("mux session dial is backing off for native QUIC (2s remaining)")
            }
            42L
        }

        assertEquals(42L, elapsed)
        assertEquals(listOf(3_000L, 3_000L), attemptTimeouts)
        assertEquals(3_050L, now)
    }

    @Test
    fun doesNotRetryUnrelatedProbeFailures() {
        val failure = IllegalStateException("authentication failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            probeOutboundWithTransientQuicRetry(20_000L, 3_000L) { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun doesNotWaitPastTheOverallTcpingBudget() {
        val failure = IllegalStateException("mux session dial is backing off for native QUIC (30s remaining)")
        var pauses = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            probeOutboundWithTransientQuicRetry(
                totalTimeoutMillis = 20_000L,
                attemptTimeoutMillis = 3_000L,
                pause = { pauses += 1 },
            ) { throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(0, pauses)
    }

    @Test
    fun cancelsPromptlyWhileWaitingForMuxBackoff() {
        var active = true
        var pauses = 0
        assertThrows(CancellationException::class.java) {
            probeOutboundWithTransientQuicRetry(
                totalTimeoutMillis = 20_000L,
                attemptTimeoutMillis = 3_000L,
                isActive = { active },
                pause = {
                    pauses += 1
                    active = false
                },
            ) {
                throw IllegalStateException(
                    "mux session dial is backing off for native QUIC (10s remaining)",
                )
            }
        }
        assertEquals(1, pauses)
    }

    @Test
    fun retriesCanceledStreamThenNativeQuicBackoffThenSucceeds() {
        var now = 0L
        var attempts = 0
        val result = probeOutboundWithTransientQuicRetry(
            totalTimeoutMillis = 20_000L,
            attemptTimeoutMillis = 3_000L,
            nowMillis = { now },
            pause = { now += it },
        ) {
            attempts += 1
            when (attempts) {
                1 -> throw IllegalStateException("close called for canceled stream 0")
                2 -> throw IllegalStateException(
                    "mux session dial is backing off for native QUIC (250ms remaining)",
                )
                else -> 88L
            }
        }

        assertEquals(88L, result)
        assertEquals(3, attempts)
        assertEquals(400L, now)
    }

    @Test
    fun retriesRejectedPeerUniStreamThenNativeQuicBackoffThenSucceeds() {
        var now = 0L
        var attempts = 0
        val result = probeOutboundWithTransientQuicRetry(
            totalTimeoutMillis = 20_000L,
            attemptTimeoutMillis = 3_000L,
            nowMillis = { now },
            pause = { now += it },
        ) {
            attempts += 1
            when (attempts) {
                1 -> throw IllegalStateException(
                    "STREAM_LIMIT_ERROR (local): peer tried to open stream 3 (current limit: -1)",
                )
                2 -> throw IllegalStateException(
                    "mux session dial is backing off for native QUIC (1.458s remaining)",
                )
                else -> 91L
            }
        }

        assertEquals(91L, result)
        assertEquals(3, attempts)
        assertEquals(1_608L, now)
    }
}
