package com.tcptun.client

import java.util.concurrent.CancellationException
import kotlin.math.ceil

private val MuxBackoffRemainingPattern = Regex(
    """mux session dial is backing off for native QUIC \(([0-9]+(?:\.[0-9]+)?)(ms|s) remaining\)""",
)
private val CanceledQuicStreamPattern = Regex("""close called for canceled stream \d+""")
private val RejectedPeerUniStreamPattern = Regex(
    """STREAM_LIMIT_ERROR.*peer tried to open stream \d+ \(current limit: -1\)""",
    RegexOption.IGNORE_CASE,
)

internal fun transientQuicRetryDelayMillis(error: Throwable): Long? {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        val message = cause.message.orEmpty().take(MAX_RETRY_ERROR_LENGTH)
        MuxBackoffRemainingPattern.find(message)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = if (match.groupValues[2] == "s") 1_000.0 else 1.0
            val milliseconds = ceil(value * multiplier)
            if (!milliseconds.isFinite() || milliseconds > Long.MAX_VALUE - 50L) return null
            return (milliseconds.toLong() + 50L).coerceAtLeast(1L)
        }
        if (
            CanceledQuicStreamPattern.containsMatchIn(message) ||
            RejectedPeerUniStreamPattern.containsMatchIn(message)
        ) {
            // A failed native-QUIC carrier records its own backoff immediately
            // after surfacing these uQUIC carrier errors. Retry shortly after;
            // the next attempt will either succeed or expose the exact backoff.
            return 100L
        }
        cause = cause.cause
        depth += 1
    }
    return null
}

internal fun probeOutboundWithTransientQuicRetry(
    totalTimeoutMillis: Long,
    attemptTimeoutMillis: Long,
    isActive: () -> Boolean = { true },
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    pause: (Long) -> Unit = Thread::sleep,
    probe: (Long) -> Long,
): Long {
    require(totalTimeoutMillis > 0) { "TCPing total timeout must be positive" }
    require(attemptTimeoutMillis > 0) { "TCPing attempt timeout must be positive" }
    val startedAt = nowMillis()
    val deadline = if (startedAt > Long.MAX_VALUE - totalTimeoutMillis) {
        Long.MAX_VALUE
    } else {
        startedAt + totalTimeoutMillis
    }
    while (true) {
        if (!isActive()) throw CancellationException("TCPing request was canceled")
        val remaining = deadline - nowMillis()
        if (remaining <= 0) throw IllegalStateException("TCPing retry deadline elapsed")
        try {
            return probe(attemptTimeoutMillis.coerceAtMost(remaining))
        } catch (error: Exception) {
            val retryDelay = transientQuicRetryDelayMillis(error) ?: throw error
            val retryBudget = deadline - nowMillis()
            if (retryDelay >= retryBudget) throw error
            var delayRemaining = retryDelay
            while (delayRemaining > 0) {
                if (!isActive()) throw CancellationException("TCPing request was canceled")
                val slice = delayRemaining.coerceAtMost(100L)
                pause(slice)
                delayRemaining -= slice
            }
        }
    }
}

private const val MAX_CAUSE_DEPTH = 32
private const val MAX_RETRY_ERROR_LENGTH = 4_096
