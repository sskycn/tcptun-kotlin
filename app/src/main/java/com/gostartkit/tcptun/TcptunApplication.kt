package com.tcptun.client

import android.app.Application
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the small amount of work that must outlive a Compose screen but remain
 * bounded by the Android process. The VPN service has its own executor and
 * lifecycle; these scopes are only for dispatching UI-originated commands and
 * durable mutations.
 */
internal class TcptunApplication : Application() {
    private val processJob = SupervisorJob()

    internal val vpnPlanCommandScope = CoroutineScope(
        processJob + Dispatchers.Main.immediate + VpnPlanCommandExceptionHandler,
    )
    internal val durableMutationScope = CoroutineScope(processJob + Dispatchers.IO)

    override fun onTerminate() {
        processJob.cancel()
        super.onTerminate()
    }
}

private val VpnPlanCommandExceptionHandler = CoroutineExceptionHandler { _, error ->
    if (error.isFatalProcessError()) throw error
    runRecoverableCatching {
        TcptunState.appendLog("VPN command coroutine failed: ${failureDescription(error)}")
    }
}
