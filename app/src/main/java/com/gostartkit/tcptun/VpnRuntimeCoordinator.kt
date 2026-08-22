package com.tcptun.client

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Commands admitted to the serialized VPN runtime mutation lane. */
internal sealed interface VpnRuntimeCommand {
    val description: String

    data object Start : VpnRuntimeCommand { override val description = "VPN start" }
    data object Stop : VpnRuntimeCommand { override val description = "VPN stop" }
    data object UpdateOutbounds : VpnRuntimeCommand { override val description = "VPN outbound update" }
    data object ApplyRuntimeSettings : VpnRuntimeCommand { override val description = "runtime settings apply" }
    data object BridgeRecovery : VpnRuntimeCommand { override val description = "Bridge recovery" }
    data object UpdateFlowAnalysis : VpnRuntimeCommand { override val description = "flow analysis update" }
    data object RefreshDiagnostics : VpnRuntimeCommand { override val description = "Bridge diagnostics refresh" }
    data class Internal(override val description: String) : VpnRuntimeCommand
}

/**
 * Owns admission and in-flight accounting for runtime mutations. Existing epoch/session/lease
 * checks remain inside the operations; this coordinator only makes their execution single-writer.
 */
internal class VpnRuntimeCoordinator(
    private val executor: Executor,
    private val canExecute: () -> Boolean,
) {
    private val workInFlight = AtomicInteger()

    val inFlight: Int
        get() = workInFlight.get()

    fun dispatch(
        command: VpnRuntimeCommand,
        onFailure: (Throwable) -> Unit,
        operation: () -> Unit,
    ): Boolean {
        if (!canExecute()) return false
        workInFlight.incrementAndGet()
        val accepted = executeCrashGuarded(executor, command.description, onFailure) {
            try {
                if (canExecute()) operation()
            } finally {
                workInFlight.decrementAndGet()
            }
        }
        if (!accepted) workInFlight.decrementAndGet()
        return accepted
    }
}
