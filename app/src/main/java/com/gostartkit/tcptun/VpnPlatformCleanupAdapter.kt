package com.tcptun.client

internal data class VpnCleanupAttempt(
    val result: VpnPlatformStopResult,
    val bridgeStopFailure: Throwable?,
)

/** Keeps old/new Service publication linearization outside the cleanup adapter. */
internal class VpnCleanupPublicationPort(
    val globalStep: (label: String, action: () -> Unit) -> Unit,
    val localStep: (label: String, action: () -> Unit) -> Unit,
)

/** Narrow Service callbacks used by exactly one physical platform-cleanup attempt. */
internal data class VpnPlatformCleanupActions(
    val cancelBridgeRestart: () -> Unit,
    val publishStopping: () -> Unit,
    val stopHealth: () -> Unit,
    val unregisterNetwork: () -> Unit,
    val resetUnderlyingDiagnostics: () -> Unit,
    val clearDesiredConfig: () -> Unit,
    val publishBridgeStopping: () -> Unit,
    val stopBridgeSession: () -> Unit,
    val closeTunIfSafe: () -> Unit,
    val clearAppIdentity: () -> Unit,
    val resetHealth: () -> Unit,
    val resourcesOwned: () -> Boolean,
    val resetDiagnostics: () -> Unit,
    val publishStopped: () -> Unit,
    val removeForeground: () -> Unit,
    val requestServiceStop: () -> Unit,
    val honorDeferredStopIfReleased: () -> Unit,
    val publishIncompleteCleanup: (description: String) -> Unit,
    val retainCleanupForeground: () -> Unit,
)

/**
 * Executes one cleanup attempt. Retry generations and coordinator completion
 * deliberately remain owned by [TcptunVpnService].
 */
internal class VpnPlatformCleanupAdapter(
    private val actions: VpnPlatformCleanupActions,
) {
    fun perform(
        request: VpnPlatformTeardownRequest,
        publication: VpnCleanupPublicationPort,
    ): VpnCleanupAttempt {
        var bridgeStopFailure: Throwable? = null
        actions.cancelBridgeRestart()
        if (request.setStopped) {
            publication.globalStep("set stopping state", actions.publishStopping)
        }
        publication.localStep("stop bridge monitor", actions.stopHealth)
        publication.localStep("unregister network callback", actions.unregisterNetwork)
        publication.globalStep(
            "reset underlying network diagnostics",
            actions.resetUnderlyingDiagnostics,
        )
        if (request.clearSavedConfig) {
            publication.globalStep("clear saved VPN config", actions.clearDesiredConfig)
        }
        publication.localStep("log bridge stop", actions.publishBridgeStopping)
        // Native stop must release the Go-owned TUN duplicate before the
        // Service callback may close Android's original descriptor.
        try {
            actions.stopBridgeSession()
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            bridgeStopFailure = error
            publication.localStep("report tcptun bridge stop failure") { throw error }
        }
        publication.localStep("close VPN TUN", actions.closeTunIfSafe)
        publication.localStep("clear app identity cache", actions.clearAppIdentity)
        actions.resetHealth()

        val disposition = bridgeTeardownDisposition(actions.resourcesOwned())
        if (disposition.resourcesReleased) {
            completeReleased(request, disposition, publication)
        } else {
            completeRetained(bridgeStopFailure, publication)
        }
        return VpnCleanupAttempt(
            result = if (disposition.resourcesReleased) {
                VpnPlatformStopResult.Released
            } else {
                VpnPlatformStopResult.RetainedForRetry
            },
            bridgeStopFailure = bridgeStopFailure,
        )
    }

    private fun completeReleased(
        request: VpnPlatformTeardownRequest,
        disposition: BridgeTeardownDisposition,
        publication: VpnCleanupPublicationPort,
    ) {
        publication.globalStep("reset VPN diagnostics", actions.resetDiagnostics)
        if (request.setStopped && disposition.mayPublishStopped) {
            publication.globalStep("set stopped state", actions.publishStopped)
        }
        if (disposition.mayRemoveForeground) {
            publication.globalStep(
                "remove VPN foreground notification",
                actions.removeForeground,
            )
        }
        if (request.stopSelfService && disposition.mayStopService) {
            publication.globalStep("stop VPN service", actions.requestServiceStop)
        }
        publication.localStep(
            "honor deferred VPN service stop",
            actions.honorDeferredStopIfReleased,
        )
    }

    private fun completeRetained(
        bridgeStopFailure: Throwable?,
        publication: VpnCleanupPublicationPort,
    ) {
        val description = bridgeStopFailure?.let(::failureDescription)
            ?: "native bridge resources are still owned"
        publication.globalStep("publish incomplete VPN teardown") {
            actions.publishIncompleteCleanup(description)
        }
        publication.globalStep(
            "retain VPN cleanup foreground",
            actions.retainCleanupForeground,
        )
    }
}
