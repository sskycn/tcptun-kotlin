package com.tcptun.client

import android.os.SystemClock
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Owns the settle window and single delayed wake used by member health probes. */
internal class MemberHealthProbeScheduler(
    private val executor: ScheduledExecutorService,
    private val canRun: () -> Boolean,
    private val markProbeForced: () -> Unit,
    private val wakeMonitor: () -> Unit,
    private val log: (String) -> Unit,
    private val maxDelayMs: Long,
) {
    private val lock = Any()
    private val notBeforeElapsedMs = AtomicLong(0L)
    private val generation = AtomicInteger()
    private val delayedTask = LatestTaskSlot()

    val notBeforeMs: Long
        get() = notBeforeElapsedMs.get()

    fun schedule(reason: String, requestedDelayMs: Long) {
        val delayMs = requestedDelayMs.coerceIn(0L, maxDelayMs)
        if (!canRun()) return
        markProbeForced()
        synchronized(lock) {
            if (delayMs == 0L) {
                notBeforeElapsedMs.set(0L)
                generation.incrementAndGet()
                delayedTask.cancel()
                log("bridge health check requested: $reason")
                wakeMonitor()
                return
            }
            val notBefore = SystemClock.elapsedRealtime() + delayMs
            notBeforeElapsedMs.updateAndGet { current -> maxOf(current, notBefore) }
            val scheduledGeneration = generation.incrementAndGet()
            val scheduledDelay = (
                notBeforeElapsedMs.get() - SystemClock.elapsedRealtime()
            ).coerceAtLeast(0L)
            log("member health probe scheduled in ${delayMs}ms: $reason")
            val future = scheduleCrashGuardedFuture(
                executor = executor,
                delay = scheduledDelay,
                unit = TimeUnit.MILLISECONDS,
                taskName = "delayed member health probe",
                onFailure = { error -> log(failureDescription(error)) },
            ) delayedProbe@{
                if (scheduledGeneration != generation.get() || !canRun()) return@delayedProbe
                markProbeForced()
                log("bridge health check requested: $reason")
                wakeMonitor()
            }
            if (future == null) {
                log("member health probe scheduling failed; requesting an immediate check")
                markProbeForced()
                wakeMonitor()
            } else {
                delayedTask.replace(future)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            notBeforeElapsedMs.set(0L)
            generation.incrementAndGet()
            delayedTask.cancel()
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation.incrementAndGet()
            delayedTask.cancel()
        }
    }
}
