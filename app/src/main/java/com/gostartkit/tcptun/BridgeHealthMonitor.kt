package com.tcptun.client

internal data class BridgeHealthCheckSchedule(
    val eventDriven: Boolean,
    val intervalSeconds: Long,
)

/**
 * Runs the event-driven bridge health loop without owning Android or native
 * resources. Callers supply session validity, waiting, probing, and effects.
 */
internal class BridgeHealthMonitorLoop(
    private val failureLimit: Int,
    private val nextCheckDelayMillis: (confirmingFailure: Boolean) -> Long?,
) {
    init {
        require(failureLimit > 0) { "bridge health failure limit must be positive" }
    }

    fun run(
        initialWakeGeneration: Int,
        isCurrent: () -> Boolean,
        canProbe: () -> Boolean,
        awaitEvent: (handledWakeGeneration: Int, timeoutMillis: Long?) -> Int,
        probeFailureReason: () -> String?,
        onSchedule: (BridgeHealthCheckSchedule) -> Unit,
        onFailure: (String) -> Unit,
        onRestartRequired: (String) -> Unit,
        onRecoverableError: (Throwable) -> Unit,
    ) {
        var consecutiveFailures = 0
        var handledWakeGeneration = initialWakeGeneration
        while (isCurrent() && !Thread.currentThread().isInterrupted) {
            try {
                val delayMillis = nextCheckDelayMillis(consecutiveFailures > 0)
                onSchedule(
                    BridgeHealthCheckSchedule(
                        eventDriven = delayMillis == null,
                        intervalSeconds = delayMillis?.div(1_000L) ?: 0L,
                    ),
                )
                // Retain the generation actually consumed. A wake arriving
                // during probe execution remains pending for the next pass.
                handledWakeGeneration = awaitEvent(handledWakeGeneration, delayMillis)
                if (!canProbe()) continue
                val failureReason = probeFailureReason()
                if (!isCurrent()) return
                if (failureReason == null) {
                    consecutiveFailures = 0
                    continue
                }
                onFailure(failureReason)
                consecutiveFailures += 1
                if (consecutiveFailures >= failureLimit) {
                    consecutiveFailures = 0
                    onRestartRequired(failureReason)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                onRecoverableError(error)
            }
        }
    }
}
