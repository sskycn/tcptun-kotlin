package com.tcptun.client

import java.util.concurrent.atomic.AtomicLong

/** Passive lifecycle counters for tests and on-demand diagnostics; never publishes or schedules. */
internal object PowerSavingObservability {
    private val memberProbeScheduled = AtomicLong()
    private val memberProbeCoalesced = AtomicLong()
    private val memberProbeCanceledNoNetwork = AtomicLong()
    private val bridgeMonitorEventWakes = AtomicLong()
    private val nativeFlowCallbackAttaches = AtomicLong()
    private val nativeFlowCallbackDetaches = AtomicLong()
    private val nativeLogCallbackAttaches = AtomicLong()
    private val nativeLogCallbackDetaches = AtomicLong()

    fun memberProbeScheduled() { memberProbeScheduled.incrementAndGet() }
    fun memberProbeCoalesced() { memberProbeCoalesced.incrementAndGet() }
    fun memberProbeCanceledNoNetwork() { memberProbeCanceledNoNetwork.incrementAndGet() }
    fun bridgeMonitorEventWake() { bridgeMonitorEventWakes.incrementAndGet() }
    fun nativeFlowCallbackChanged(attached: Boolean) {
        if (attached) nativeFlowCallbackAttaches.incrementAndGet()
        else nativeFlowCallbackDetaches.incrementAndGet()
    }
    fun nativeLogCallbackChanged(attached: Boolean) {
        if (attached) nativeLogCallbackAttaches.incrementAndGet()
        else nativeLogCallbackDetaches.incrementAndGet()
    }

    fun snapshot() = PowerSavingObservabilitySnapshot(
        memberProbeScheduled = memberProbeScheduled.get(),
        memberProbeCoalesced = memberProbeCoalesced.get(),
        memberProbeCanceledNoNetwork = memberProbeCanceledNoNetwork.get(),
        bridgeMonitorEventWakes = bridgeMonitorEventWakes.get(),
        nativeFlowCallbackAttaches = nativeFlowCallbackAttaches.get(),
        nativeFlowCallbackDetaches = nativeFlowCallbackDetaches.get(),
        nativeLogCallbackAttaches = nativeLogCallbackAttaches.get(),
        nativeLogCallbackDetaches = nativeLogCallbackDetaches.get(),
    )

    internal fun resetForTest() {
        listOf(
            memberProbeScheduled,
            memberProbeCoalesced,
            memberProbeCanceledNoNetwork,
            bridgeMonitorEventWakes,
            nativeFlowCallbackAttaches,
            nativeFlowCallbackDetaches,
            nativeLogCallbackAttaches,
            nativeLogCallbackDetaches,
        ).forEach { it.set(0L) }
    }
}

internal data class PowerSavingObservabilitySnapshot(
    val memberProbeScheduled: Long,
    val memberProbeCoalesced: Long,
    val memberProbeCanceledNoNetwork: Long,
    val bridgeMonitorEventWakes: Long,
    val nativeFlowCallbackAttaches: Long,
    val nativeFlowCallbackDetaches: Long,
    val nativeLogCallbackAttaches: Long,
    val nativeLogCallbackDetaches: Long,
)
