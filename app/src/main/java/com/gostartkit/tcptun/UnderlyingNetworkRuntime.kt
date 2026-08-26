package com.tcptun.client

internal interface UnderlyingNetworkSelectionSource<N> {
    fun register()
    fun unregister(): Boolean
    fun republishCurrent(reason: String)
}

internal data class UnderlyingNetworkUpdate<N>(
    val network: N?,
    val selection: RankedSelectionClaim<N>,
    val reason: String,
    val ownership: VpnRuntimeOwnership,
    val sequence: Long,
)

/** Coalesces selection changes while retaining their physical runtime ownership. */
internal class UnderlyingNetworkUpdateGate<N> {
    private var sequence = 0L

    @Synchronized
    fun request(
        network: N?,
        selection: RankedSelectionClaim<N>,
        reason: String,
        ownership: VpnRuntimeOwnership,
    ): UnderlyingNetworkUpdate<N> = UnderlyingNetworkUpdate(
        network,
        selection,
        reason,
        ownership,
        nextSequence(),
    )

    @Synchronized
    fun invalidate() {
        nextSequence()
    }

    @Synchronized
    fun isLatest(request: UnderlyingNetworkUpdate<N>): Boolean = request.sequence == sequence

    private fun nextSequence(): Long {
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
        return sequence
    }
}

/** Owns underlying-network callback, selection, and handover control flow for one Service. */
internal class UnderlyingNetworkRuntime<N>(
    selectionSourceFactory: (
        onSelectionChanged: (N?, RankedSelectionClaim<N>, String) -> Unit,
    ) -> UnderlyingNetworkSelectionSource<N>,
    private val currentOwnership: () -> VpnRuntimeOwnership?,
    private val isOwnershipCurrent: (VpnRuntimeOwnership) -> Boolean,
    private val dispatchLifecycle: (VpnRuntimeOwnership, () -> Unit) -> Boolean,
    private val applyUnderlyingNetworks: (N?) -> Unit,
    private val updateDiagnostics: (N?) -> Unit,
    private val vpnRunning: () -> Boolean,
    private val onRestartRequested: (String, Long, VpnRuntimeOwnership) -> Unit,
    private val onMemberProbeRequested: (String, Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private val updateGate = UnderlyingNetworkUpdateGate<N>()
    private val selectionSource = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        selectionSourceFactory(::onSelectionChanged)
    }

    @Volatile
    private var acceptingSelections = false

    fun register() {
        acceptingSelections = true
        selectionSource.value.register()
    }

    fun unregister(updateDiagnostics: Boolean = true) {
        acceptingSelections = false
        updateGate.invalidate()
        if (!selectionSource.isInitialized()) return
        val unregistered = selectionSource.value.unregister()
        if (unregistered && updateDiagnostics) clearDiagnostics()
    }

    fun republishCurrent(reason: String) {
        if (acceptingSelections && selectionSource.isInitialized()) {
            selectionSource.value.republishCurrent(reason)
        }
    }

    fun clearDiagnostics() {
        updateDiagnostics(null)
    }

    private fun onSelectionChanged(
        network: N?,
        selection: RankedSelectionClaim<N>,
        reason: String,
    ) {
        if (!acceptingSelections) return
        val ownership = currentOwnership() ?: return
        val update = updateGate.request(network, selection, reason, ownership)
        dispatchLifecycle(ownership) { applyUpdate(update) }
    }

    private fun applyUpdate(update: UnderlyingNetworkUpdate<N>) {
        if (!isCurrent(update)) return
        updateDiagnostics(update.network)
        log("underlying network selected: ${update.network ?: "none"}")
        if (!isCurrent(update)) return
        try {
            applyUnderlyingNetworks(update.network)
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            log("set underlying network failed: ${failureDescription(error)}")
        }
        if (!isCurrent(update)) return
        if (BridgeHealthPolicy.shouldRestartForNetworkHandover(
                initialSelection = update.selection.initial,
                networkAvailable = update.network != null,
                vpnRunning = vpnRunning(),
                previousNetworkAvailable = update.selection.previousValue != null,
            )
        ) {
            onRestartRequested(
                update.reason,
                BridgeHealthPolicy.NETWORK_HANDOVER_SETTLE_MS,
                update.ownership,
            )
        } else if (update.network != null) {
            onMemberProbeRequested(
                update.reason,
                BridgeHealthPolicy.MEMBER_HEALTH_STARTUP_DELAY_MS,
            )
        } else {
            // A member probe cannot succeed without an eligible underlying network. Waiting for
            // the next connectivity callback avoids waking the process/radio only to fail and
            // enqueue another transient retry while the device is offline or in Doze.
            log("member health probe skipped: no underlying network")
        }
    }

    private fun isCurrent(update: UnderlyingNetworkUpdate<N>): Boolean =
        acceptingSelections && updateGate.isLatest(update) && isOwnershipCurrent(update.ownership)
}
