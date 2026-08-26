package com.tcptun.client

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberHealthProbeSchedulerTest {
    @Test
    fun shorterDelayedRequestReusesExistingSettleWake() {
        val executor = CountingScheduledExecutor()
        var now = 1_000L
        val scheduler = scheduler(executor) { now }
        try {
            scheduler.schedule("network available", requestedDelayMs = 4_000L)
            now = 1_500L
            scheduler.schedule("membership changed", requestedDelayMs = 2_000L)

            assertEquals(1, executor.scheduleCalls)
            assertEquals(5_000L, scheduler.notBeforeMs)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun laterDelayedRequestExtendsSettleWake() {
        val executor = CountingScheduledExecutor()
        var now = 1_000L
        val scheduler = scheduler(executor) { now }
        try {
            scheduler.schedule("membership changed", requestedDelayMs = 2_000L)
            now = 1_500L
            scheduler.schedule("network available", requestedDelayMs = 4_000L)

            assertEquals(2, executor.scheduleCalls)
            assertEquals(5_500L, scheduler.notBeforeMs)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelClearsPendingSettleDeadline() {
        val executor = CountingScheduledExecutor()
        val scheduler = scheduler(executor) { 1_000L }
        try {
            scheduler.schedule("network available", requestedDelayMs = 4_000L)
            scheduler.cancel()

            assertEquals(0L, scheduler.notBeforeMs)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun scheduler(
        executor: CountingScheduledExecutor,
        now: () -> Long,
    ) = MemberHealthProbeScheduler(
        executor = executor,
        canRun = { true },
        markProbeForced = {},
        wakeMonitor = {},
        log = {},
        maxDelayMs = 60_000L,
        elapsedRealtimeMs = now,
    )

    private class CountingScheduledExecutor : ScheduledThreadPoolExecutor(1) {
        var scheduleCalls = 0

        init {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            scheduleCalls += 1
            return super.schedule(command, delay, unit)
        }
    }
}
