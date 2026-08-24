package com.tcptun.client

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val IpListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val IpListItemSpacing = 8.dp

@Composable
internal fun IpInformationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val ipInfoController = rememberLocalIpInfo(context)
    val ipInfo = ipInfoController.info
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    val settings = rememberUiRuntimeSettings(context) ?: RuntimeSettings()
    val configuredListenAddress = RuntimeSettingsRepository.localSocksListenAddress(settings)
    val actualListenAddress = vpnState.diagnostics.bridgeListen
        .takeIf { vpnState.status == VpnStatus.Running }
        .orEmpty()
    val effectiveListenAddress = actualListenAddress.ifBlank { configuredListenAddress }
    val listenerNetwork = mixedListenerNetworkDisplay(
        listenAddress = effectiveListenAddress,
        underlyingIpv4 = ipInfo.underlyingIpv4,
        underlyingGatewayIpv4 = ipInfo.underlyingGatewayIpv4,
        hotspotIpv4 = ipInfo.hotspotIpv4,
    )
    val proxyAccess = proxyAccessDisplay(
        listenAddress = effectiveListenAddress,
        hotspotIpv4 = ipInfo.hotspotIpv4,
        underlyingIpv4 = ipInfo.underlyingIpv4,
        proxyRunning = vpnState.status == VpnStatus.Running,
    )
    val noneLabel = stringResource(R.string.none)

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { IpInformationTopBar(onBack = onBack) },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                ipInfoController.refresh()
                if (vpnState.status == VpnStatus.Running) {
                    runRecoverableCatching {
                        context.startService(TcptunVpnService.refreshClientIpsIntent(context))
                    }
                    delay(PullRefreshSettleMillis)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = IpListContentPadding,
                verticalArrangement = Arrangement.spacedBy(IpListItemSpacing),
            ) {
                item {
                    IpInformationCard(
                        title = stringResource(R.string.ip_mixed_listener),
                        icon = Icons.Rounded.Lan,
                        lines = listOf(
                            stringResource(R.string.ip_configured_listen) to configuredListenAddress,
                            stringResource(R.string.ip_actual_listen) to actualListenAddress.ifBlank { noneLabel },
                            stringResource(R.string.ip_listener_ipv4) to listenerNetwork.ipv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_gateway_ipv4) to listenerNetwork.gatewayIpv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_hotspot_interface) to ipInfo.hotspotInterface.ifBlank { noneLabel },
                            stringResource(R.string.ip_hotspot_ipv4) to ipInfo.hotspotIpv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_client_proxy_address) to proxyAccess.address.ifBlank { noneLabel },
                        ),
                    )
                }
                item {
                    val clientIps = vpnState.diagnostics.bridgeClientIps
                    IpInformationCard(
                        title = stringResource(R.string.ip_connected_clients),
                        icon = Icons.Rounded.Hub,
                        lines = if (clientIps.isEmpty()) {
                            listOf(stringResource(R.string.ip_client_status) to stringResource(R.string.ip_no_connected_clients))
                        } else {
                            clientIps.mapIndexed { index, ip ->
                                stringResource(R.string.ip_client_number, index + 1) to ip
                            }
                        },
                    )
                }
                item {
                    IpInformationCard(
                        title = stringResource(R.string.ip_underlying_network),
                        icon = Icons.Rounded.Router,
                        lines = listOf(
                            stringResource(R.string.ip_underlying_interface) to ipInfo.underlyingInterface.ifBlank { noneLabel },
                            stringResource(R.string.ip_underlying_ipv4) to ipInfo.underlyingIpv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_gateway_ipv4) to ipInfo.underlyingGatewayIpv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_underlying_ipv6) to ipInfo.underlyingIpv6.ifBlank { noneLabel },
                        ),
                    )
                }
                item {
                    IpInformationCard(
                        title = stringResource(R.string.ip_vpn_network),
                        icon = Icons.Rounded.Hub,
                        lines = listOf(
                            stringResource(R.string.ip_vpn_ipv4) to ipInfo.vpnIpv4.ifBlank { noneLabel },
                            stringResource(R.string.ip_vpn_ipv6) to ipInfo.vpnIpv6.ifBlank { noneLabel },
                        ),
                    )
                }
                item {
                    Text(
                        stringResource(R.string.ip_information_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun IpInformationCard(
    title: String,
    icon: ImageVector,
    lines: List<Pair<String, String>>,
) {
    SettingsCard {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle(icon = icon, title = title)
                lines.forEach { (label, value) -> DiagnosticsLine(label, value) }
            }
        }
    }
}

@Composable
private fun IpInformationTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.ip_information),
        onBack = onBack,
    )
}

@Composable
internal fun rememberUiRuntimeSettings(context: Context): RuntimeSettings? {
    return androidx.compose.runtime.produceState<RuntimeSettings?>(initialValue = null, context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readUiRuntimeSettings(context).uiFallbackSettings()
        }
    }.value
}
