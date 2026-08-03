package com.tcptun.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHealthMonitorTest {
    @Test
    fun consecutiveFailuresSwitchToConfirmationTimerAndRequestRestart() {
        var active = true
        val waits = mutableListOf<Pair<Int, Long?>>()
        val failures = mutableListOf<String>()
        val restarts = mutableListOf<String>()
        val schedules = mutableListOf<BridgeHealthCheckSchedule>()

        monitor().run(
            initialWakeGeneration = 3,
            isCurrent = { active },
            canProbe = { active },
            awaitEvent = { generation, timeout ->
                waits += generation to timeout
                generation + 1
            },
            probeFailureReason = { "bridge unavailable" },
            onSchedule = schedules::add,
            onFailure = failures::add,
            onRestartRequired = { reason ->
                restarts += reason
                active = false
            },
            onRecoverableError = { throw AssertionError(it) },
        )

        assertEquals(listOf(3 to null, 4 to 2_000L), waits)
        assertEquals(
            listOf(
                BridgeHealthCheckSchedule(eventDriven = true, intervalSeconds = 0L),
                BridgeHealthCheckSchedule(eventDriven = false, intervalSeconds = 2L),
            ),
            schedules,
        )
        assertEquals(listOf("bridge unavailable", "bridge unavailable"), failures)
        assertEquals(listOf("bridge unavailable"), restarts)
    }

    @Test
    fun healthyProbeResetsConsecutiveFailureCount() {
        var active = true
        val results = ArrayDeque(listOf("first failure", null, "second failure", "confirmed failure"))
        var probes = 0
        val restarts = mutableListOf<String>()

        monitor().run(
            initialWakeGeneration = 0,
            isCurrent = { active },
            canProbe = { active },
            awaitEvent = { generation, _ -> generation + 1 },
            probeFailureReason = {
                probes += 1
                results.removeFirst()
            },
            onSchedule = {},
            onFailure = {},
            onRestartRequired = { reason ->
                restarts += reason
                active = false
            },
            onRecoverableError = { throw AssertionError(it) },
        )

        assertEquals(4, probes)
        assertEquals(listOf("confirmed failure"), restarts)
    }

    @Test
    fun staleSessionSkipsProbeAfterWake() {
        var current = true
        var probes = 0

        monitor().run(
            initialWakeGeneration = 5,
            isCurrent = { current },
            canProbe = {
                current = false
                false
            },
            awaitEvent = { generation, _ -> generation + 1 },
            probeFailureReason = {
                probes += 1
                null
            },
            onSchedule = {},
            onFailure = {},
            onRestartRequired = {},
            onRecoverableError = { throw AssertionError(it) },
        )

        assertEquals(0, probes)
    }

    @Test
    fun recoverableProbeErrorIsReportedAndLoopContinues() {
        var active = true
        var probes = 0
        val errors = mutableListOf<String>()

        monitor(failureLimit = 1).run(
            initialWakeGeneration = 0,
            isCurrent = { active },
            canProbe = { active },
            awaitEvent = { generation, _ -> generation + 1 },
            probeFailureReason = {
                probes += 1
                if (probes == 1) error("probe crashed")
                "still unhealthy"
            },
            onSchedule = {},
            onFailure = {},
            onRestartRequired = { active = false },
            onRecoverableError = { errors += it.message.orEmpty() },
        )

        assertEquals(2, probes)
        assertEquals(listOf("probe crashed"), errors)
    }

    @Test
    fun invalidFailureLimitIsRejected() {
        val failure = runCatching { monitor(failureLimit = 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun monitor(failureLimit: Int = 2): BridgeHealthMonitorLoop =
        BridgeHealthMonitorLoop(
            failureLimit = failureLimit,
            nextCheckDelayMillis = { confirming -> if (confirming) 2_000L else null },
        )
}
