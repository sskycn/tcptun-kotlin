package com.tcptun.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class ProfilesRuntimeUiState(
    val status: VpnStatus,
    val connectionsReady: Boolean,
    val lastError: String,
    val tcping: TcpingProgress,
    val profileHealth: Map<String, ProfileHealth>,
    val bridgeListen: String,
)

internal data class DiagnosticsRuntimeUiState(
    val status: VpnStatus,
    val diagnostics: TcptunDiagnostics,
)

internal data class IpInformationRuntimeUiState(
    val status: VpnStatus,
    val bridgeListen: String,
    val bridgeClientIps: List<String>,
)

internal data class SettingsRuntimeUiState(
    val mtu: Int,
)

internal fun selectVpnStatus(state: TcptunRuntimeState): VpnStatus = state.status

internal fun selectDiagnostics(state: TcptunRuntimeState): TcptunDiagnostics = state.diagnostics

internal fun selectLogs(state: TcptunRuntimeState): List<String> = state.logs

internal fun selectTcping(state: TcptunRuntimeState): TcpingProgress = state.tcping

internal fun selectProfileHealth(state: TcptunRuntimeState): Map<String, ProfileHealth> = state.profileHealth

internal fun selectFlowAnalysisApp(state: TcptunRuntimeState): String = state.flowAnalysisApp

internal fun selectProfileStateRevision(state: TcptunRuntimeState): Long = state.profileStateRevision

internal fun selectProfilesRuntimeUi(state: TcptunRuntimeState) = ProfilesRuntimeUiState(
    status = state.status,
    connectionsReady = state.connectionsReady,
    lastError = state.lastError,
    tcping = state.tcping,
    profileHealth = state.profileHealth,
    bridgeListen = state.diagnostics.bridgeListen,
)

internal fun selectDiagnosticsRuntimeUi(state: TcptunRuntimeState) = DiagnosticsRuntimeUiState(
    status = state.status,
    diagnostics = state.diagnostics,
)

internal fun selectIpInformationRuntimeUi(state: TcptunRuntimeState) = IpInformationRuntimeUiState(
    status = state.status,
    bridgeListen = state.diagnostics.bridgeListen,
    bridgeClientIps = state.diagnostics.bridgeClientIps,
)

internal fun selectSettingsRuntimeUi(state: TcptunRuntimeState) = SettingsRuntimeUiState(
    mtu = state.diagnostics.mtu,
)

internal fun <T> Flow<TcptunRuntimeState>.selectRuntimeState(
    selector: (TcptunRuntimeState) -> T,
): Flow<T> = map(selector).distinctUntilChanged()
