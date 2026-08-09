package com.tcptun.client

internal enum class VpnServiceCommand(val policyKind: ServiceCommandKind) {
    Start(ServiceCommandKind.StartOrRestore),
    Restore(ServiceCommandKind.StartOrRestore),
    Unknown(ServiceCommandKind.StartOrRestore),
    Stop(ServiceCommandKind.Stop),
    UpdateConnections(ServiceCommandKind.UpdateConnections),
    Tcping(ServiceCommandKind.Auxiliary),
    ApplyRuntimeSettings(ServiceCommandKind.Auxiliary),
    UpdateFlowAnalysis(ServiceCommandKind.Auxiliary),
    RefreshClientIps(ServiceCommandKind.Auxiliary),
    ;

    val requiresForegroundStart: Boolean
        get() = this == Start || this == Restore

    companion object {
        fun fromAction(action: String?): VpnServiceCommand = when (action) {
            TcptunVpnService.ACTION_START -> Start
            TcptunVpnService.ACTION_STOP -> Stop
            TcptunVpnService.ACTION_UPDATE_OUTBOUNDS -> UpdateConnections
            TcptunVpnService.ACTION_TCPING_OUTBOUNDS -> Tcping
            TcptunVpnService.ACTION_APPLY_RUNTIME_SETTINGS -> ApplyRuntimeSettings
            TcptunVpnService.ACTION_UPDATE_FLOW_ANALYSIS -> UpdateFlowAnalysis
            TcptunVpnService.ACTION_REFRESH_CLIENT_IPS -> RefreshClientIps
            null -> Restore
            else -> Unknown
        }
    }
}
