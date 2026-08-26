package com.tcptun.client

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberHealthProbeSchedulerTest {
    @Test
    fun shorterDelayedRequestReusesExistingSettleWake() {
        PowerSavingObservability.resetForTest()
        val executor = CountingScheduledExecutor()
        var now = 1_000L
        val scheduler = scheduler(executor) { now }
        try {
            scheduler.schedule("network available", requestedDelayMs = 4_000L)
            now = 1_500L
            scheduler.schedule("membership changed", requestedDelayMs = 2_000L)

            assertEquals(1, executor.scheduleCalls)
            assertEquals(5_000L, scheduler.notBeforeMs)
            assertEquals(1L, PowerSavingObservability.snapshot().memberProbeScheduled)
            assertEquals(1L, PowerSavingObservability.snapshot().memberProbeCoalesced)
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

    @Test
    fun unavailableRuntimeDoesNotEnqueueAStartupWake() {
        val executor = CountingScheduledExecutor()
        val scheduler = MemberHealthProbeScheduler(
            executor = executor,
            canRun = { false },
            markProbeForced = {},
            wakeMonitor = {},
            log = {},
            maxDelayMs = 60_000L,
            elapsedRealtimeMs = { 1_000L },
        )
        try {
            scheduler.schedule("vpn started", requestedDelayMs = 4_000L)

            assertEquals(0, executor.scheduleCalls)
            assertEquals(0L, scheduler.notBeforeMs)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelCannotReturnBeforeAnAlreadyClaimedCallbackFinishes() {
        val executor = ManualScheduledExecutor()
        val callbackEntered = CountDownLatch(1)
        val allowCallback = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val canRunCalls = AtomicInteger()
        val events = mutableListOf<String>()
        val scheduler = MemberHealthProbeScheduler(
            executor = executor,
            canRun = {
                // schedule() checks once before and once after taking the lock; block only the
                // delayed callback's third check so the test exercises cancel/callback ordering.
                if (canRunCalls.incrementAndGet() > 2) {
                    callbackEntered.countDown()
                    allowCallback.await()
                }
                true
            },
            markProbeForced = {},
            wakeMonitor = { synchronized(events) { events += "wake" } },
            log = {},
            maxDelayMs = 60_000L,
            elapsedRealtimeMs = { 1_000L },
        )
        scheduler.schedule("network available", requestedDelayMs = 4_000L)

        val runner = Thread { executor.tasks.single().runIgnoringCancellation() }
        runner.start()
        callbackEntered.await()
        val canceller = Thread {
            scheduler.cancel()
            synchronized(events) { events += "cancel returned" }
            cancelReturned.countDown()
        }
        canceller.start()

        // If cancel is not serialized with a callback that already claimed this generation, it
        // returns during this bounded wait and the callback subsequently becomes stale.
        val returnedWhileCallbackBlocked = cancelReturned.await(100, TimeUnit.MILLISECONDS)
        allowCallback.countDown()
        runner.join()
        canceller.join()

        assertEquals(false, returnedWhileCallbackBlocked)
        assertEquals(listOf("wake", "cancel returned"), synchronized(events) { events.toList() })
        executor.shutdownNow()
    }

    @Test
    fun cancelledGenerationRejectsAQueueThatIgnoresFutureCancellation() {
        val executor = ManualScheduledExecutor()
        var forced = 0
        var wakes = 0
        val scheduler = MemberHealthProbeScheduler(
            executor = executor,
            canRun = { true },
            markProbeForced = { forced += 1 },
            wakeMonitor = { wakes += 1 },
            log = {},
            maxDelayMs = 60_000L,
            elapsedRealtimeMs = { 1_000L },
        )
        scheduler.schedule("network available", requestedDelayMs = 4_000L)

        scheduler.cancel()
        executor.tasks.single().runIgnoringCancellation()

        assertEquals(0, forced)
        assertEquals(0, wakes)
        executor.shutdownNow()
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

    private class ManualScheduledExecutor : ScheduledThreadPoolExecutor(1) {
        val tasks = mutableListOf<ManualScheduledFuture>()

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = ManualScheduledFuture(command).also(tasks::add)
    }

    private class ManualScheduledFuture(
        private val command: Runnable,
    ) : ScheduledFuture<Unit> {
        private var cancelled = false

        fun runIgnoringCancellation() = command.run()

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled
        override fun isDone(): Boolean = false
        override fun get(): Unit = Unit
        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
    }
}
