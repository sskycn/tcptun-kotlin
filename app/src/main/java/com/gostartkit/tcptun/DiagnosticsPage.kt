package com.tcptun.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val DiagnosticsListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val DiagnosticsListItemSpacing = 8.dp

@Composable
internal fun DiagnosticsPage(onBack: () -> Unit, onShowLogs: () -> Unit) {
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    val diagnostics = vpnState.diagnostics
    val coreIdentity = remember { tcptunCoreIdentity() }
    val noneLabel = stringResource(R.string.none)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DiagnosticsTopBar(
                onBack = onBack,
                onShowLogs = onShowLogs,
            )
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = { refreshRunningDiagnostics() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = DiagnosticsListContentPadding,
                verticalArrangement = Arrangement.spacedBy(DiagnosticsListItemSpacing),
            ) {
                item {
                    SettingsCard {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SectionTitle(
                                icon = Icons.Rounded.Speed,
                                title = stringResource(R.string.runtime_diagnostics),
                            )
                            DiagnosticsLine(stringResource(R.string.diag_vpn), diagnostics.vpnStatus)
                            DiagnosticsLine(stringResource(R.string.diag_underlying_network), diagnostics.underlyingNetwork)
                            DiagnosticsLine(stringResource(R.string.diag_bridge), diagnostics.bridgeStatus)
                            DiagnosticsLine(
                                stringResource(R.string.diag_core_version),
                                coreIdentity.version.ifBlank { noneLabel },
                            )
                            DiagnosticsLine(
                                stringResource(R.string.diag_core_build),
                                coreIdentity.buildId.ifBlank { noneLabel },
                            )
                            DiagnosticsLine(stringResource(R.string.diag_go_state), diagnostics.bridgeEventState)
                            DiagnosticsLine(stringResource(R.string.diag_go_phase), diagnostics.bridgeEventPhase)
                            DiagnosticsLine(stringResource(R.string.diag_go_listen), diagnostics.bridgeListen.ifBlank { noneLabel })
                            DiagnosticsLine(stringResource(R.string.diag_go_remote), diagnostics.bridgeRemote.ifBlank { noneLabel })
                            DiagnosticsLine(stringResource(R.string.diag_go_active), diagnostics.bridgeActiveConnections.toString())
                            DiagnosticsLine(
                                stringResource(R.string.diag_mux_sessions),
                                diagnostics.bridgeMuxSessions.toString(),
                            )
                            DiagnosticsLine(
                                stringResource(R.string.diag_mux_streams),
                                diagnostics.bridgeMuxStreams.toString(),
                            )
                            DiagnosticsLine(stringResource(R.string.diag_go_error), diagnostics.bridgeLastError.ifBlank { noneLabel })
                            DiagnosticsLine(stringResource(R.string.diag_go_event_time), bridgeTimestampLabel(diagnostics.bridgeTimestampMs, noneLabel))
                            DiagnosticsLine(
                                stringResource(R.string.diag_local_proxy),
                                "${diagnostics.localProxyAddress} · ${if (diagnostics.localProxyReachable) stringResource(R.string.reachable) else stringResource(R.string.not_reachable)}",
                            )
                            DiagnosticsLine(stringResource(R.string.diag_socket_protect), if (diagnostics.socketProtectEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                            DiagnosticsLine(
                                stringResource(R.string.diag_health_interval),
                                if (diagnostics.healthCheckEventDriven) {
                                    stringResource(R.string.health_check_event_driven)
                                } else {
                                    stringResource(
                                        R.string.seconds_value,
                                        diagnostics.healthCheckIntervalSeconds,
                                    )
                                },
                            )
                            DiagnosticsLine(stringResource(R.string.recent_restart_reason), diagnostics.lastRestartReason)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsTopBar(onBack: () -> Unit, onShowLogs: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.diagnostics),
        onBack = onBack,
        actions = {
            TextButton(onClick = onShowLogs) {
                Text(stringResource(R.string.logs))
            }
        },
    )
}
