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
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lock = Any()
    private val notBeforeElapsedMs = AtomicLong(0L)
    private val generation = AtomicInteger()
    private val delayedTask = LatestTaskSlot()

    val notBeforeMs: Long
        get() = notBeforeElapsedMs.get()

    fun schedule(
        reason: String,
        requestedDelayMs: Long,
        requestCurrent: () -> Boolean = { true },
    ) {
        val delayMs = requestedDelayMs.coerceIn(0L, maxDelayMs)
        if (!canRun() || !requestCurrent()) return
        synchronized(lock) {
            // Recheck after acquiring ownership of the schedule. In particular, network loss sets
            // canRun=false before calling cancel(); either this check observes the loss or that
            // cancel waits for and invalidates the Future created below.
            if (!canRun() || !requestCurrent()) return
            if (delayMs == 0L) {
                markProbeForced()
                notBeforeElapsedMs.set(0L)
                generation.incrementAndGet()
                delayedTask.cancel()
                log("bridge health check requested: $reason")
                wakeMonitor()
                return
            }

            val now = elapsedRealtimeMs()
            val candidateNotBefore = now + delayMs
            val currentNotBefore = notBeforeElapsedMs.get()
            if (currentNotBefore > now && candidateNotBefore <= currentNotBefore) {
                // A delayed wake already covers this request. Keeping the original Future avoids
                // needless cancel/re-schedule churn when connectivity and profile events arrive in
                // the same settle window, which is especially common around Doze/network resume.
                PowerSavingObservability.memberProbeCoalesced()
                log(
                    "member health probe coalesced with pending wake in " +
                        "${currentNotBefore - now}ms: $reason",
                )
                return
            }

            val scheduledNotBefore = maxOf(currentNotBefore, candidateNotBefore)
            notBeforeElapsedMs.set(scheduledNotBefore)
            val scheduledGeneration = generation.incrementAndGet()
            val scheduledDelay = (scheduledNotBefore - elapsedRealtimeMs()).coerceAtLeast(0L)
            log("member health probe scheduled in ${scheduledDelay}ms: $reason")
            val future = scheduleCrashGuardedFuture(
                executor = executor,
                delay = scheduledDelay,
                unit = TimeUnit.MILLISECONDS,
                taskName = "delayed member health probe",
                onFailure = { error -> log(failureDescription(error)) },
            ) delayedProbe@{
                // Linearize callback execution with cancel/reschedule. Future.cancel(false) does
                // not stop a callback that has already started, so a generation check outside this
                // lock would still allow a stale callback to mark/wake after cancel() returned.
                synchronized(lock) {
                    if (scheduledGeneration != generation.get()) return@delayedProbe
                    notBeforeElapsedMs.compareAndSet(scheduledNotBefore, 0L)
                    if (!canRun() || !requestCurrent()) return@delayedProbe
                    markProbeForced()
                    log("bridge health check requested: $reason")
                    wakeMonitor()
                }
            }
            if (future == null) {
                notBeforeElapsedMs.compareAndSet(scheduledNotBefore, 0L)
                if (canRun() && requestCurrent()) {
                    log("member health probe scheduling failed; requesting an immediate check")
                    markProbeForced()
                    wakeMonitor()
                }
            } else {
                PowerSavingObservability.memberProbeScheduled()
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
            notBeforeElapsedMs.set(0L)
            generation.incrementAndGet()
            delayedTask.cancel()
        }
    }
}
