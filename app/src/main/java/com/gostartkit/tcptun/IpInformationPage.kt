package com.tcptun.client

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val IpListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val IpListItemSpacing = 8.dp

@Composable
internal fun IpInformationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ipInfoController = rememberLocalIpInfo(context)
    val ipInfo = ipInfoController.info
    val runtimeUi by TcptunState.ipInformationRuntimeUiFlow.collectAsStateWithLifecycle(
        initialValue = TcptunState.ipInformationRuntimeUi,
    )
    val settings = rememberUiRuntimeSettings(context) ?: RuntimeSettings()
    var requestedProxyUserIndex by remember { mutableIntStateOf(0) }
    val selectedProxyUserIndex = if (settings.localProxyUsers.isEmpty()) {
        -1
    } else {
        requestedProxyUserIndex.coerceIn(settings.localProxyUsers.indices)
    }
    val selectedProxyUser = settings.localProxyUsers.getOrNull(selectedProxyUserIndex)
    val configuredListenAddress = RuntimeSettingsRepository.localSocksListenAddress(settings)
    val actualListenAddress = runtimeUi.bridgeListen
        .takeIf { runtimeUi.status == VpnStatus.Running }
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
        proxyRunning = runtimeUi.status == VpnStatus.Running,
    )
    val noneLabel = stringResource(R.string.none)
    val proxyConfigurationLabel = stringResource(R.string.full_config_json)
    val proxyConfigurationCopiedMessage = stringResource(R.string.proxy_configuration_copied)
    val proxyConfigurationCopyFailedMessage = stringResource(R.string.proxy_configuration_copy_failed)
    val emptyUsernameLabel = stringResource(R.string.empty_username)
    val proxyAccountOptions = settings.localProxyUsers.mapIndexed { index, user ->
        stringResource(
            R.string.ip_proxy_account_option,
            index + 1,
            user.username.ifBlank { emptyUsernameLabel },
        )
    }
    val selectedProxyAccount = proxyAccountOptions.getOrNull(selectedProxyUserIndex)
    val proxyConfigurationLines = listOf(
        stringResource(R.string.ip_proxy_protocol) to when (settings.localProxyProtocol) {
            "mixed" -> stringResource(R.string.ip_proxy_protocol_mixed)
            else -> stringResource(R.string.ip_proxy_protocol_socks5)
        },
        stringResource(R.string.ip_proxy_server) to
            hostFromListenAddress(proxyAccess.address).ifBlank { noneLabel },
        stringResource(R.string.ip_proxy_port) to
            portFromListenAddress(proxyAccess.address).ifBlank { settings.socksPort.toString() },
        stringResource(R.string.ip_proxy_username) to selectedProxyUser?.username.orEmpty().ifBlank { noneLabel },
        stringResource(R.string.ip_proxy_password) to if (settings.localProxyUsers.isEmpty()) noneLabel else "••••••••",
    )

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { IpInformationTopBar(onBack = onBack) },
        snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                ipInfoController.refresh()
                if (runtimeUi.status == VpnStatus.Running) {
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
                    ProxyConfigurationCard(
                        lines = proxyConfigurationLines,
                        accountOptions = proxyAccountOptions,
                        selectedAccount = selectedProxyAccount,
                        onAccountSelected = { selected ->
                            proxyAccountOptions.indexOf(selected)
                                .takeIf { it >= 0 }
                                ?.let { requestedProxyUserIndex = it }
                        },
                        copyEnabled = proxyAccess.address.isNotBlank(),
                        onCopy = {
                            val copied = copyProxyConfiguration(
                                context = context,
                                label = proxyConfigurationLabel,
                                text = tcptunGoProxyConfigurationJson(
                                    proxyAddress = proxyAccess.address,
                                    username = selectedProxyUser?.username.orEmpty(),
                                    password = selectedProxyUser?.password.orEmpty(),
                                ),
                                sensitive = selectedProxyUser != null,
                            )
                            scope.launch {
                                snackbarHostState.showDismissibleSnackbar(
                                    if (copied) {
                                        proxyConfigurationCopiedMessage
                                    } else {
                                        proxyConfigurationCopyFailedMessage
                                    },
                                )
                            }
                        },
                    )
                }
                item {
                    val clientIps = runtimeUi.bridgeClientIps
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
private fun ProxyConfigurationCard(
    lines: List<Pair<String, String>>,
    accountOptions: List<String>,
    selectedAccount: String?,
    onAccountSelected: (String) -> Unit,
    copyEnabled: Boolean,
    onCopy: () -> Unit,
) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(
                icon = Icons.Rounded.SettingsEthernet,
                title = stringResource(R.string.ip_proxy_configuration),
            )
            if (selectedAccount != null) {
                ChoiceRow(
                    title = stringResource(R.string.ip_proxy_account),
                    value = selectedAccount,
                    options = accountOptions,
                    onChange = onAccountSelected,
                )
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.forEach { (label, value) -> DiagnosticsLine(label, value) }
                }
            }
            FilledTonalButton(
                onClick = onCopy,
                enabled = copyEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.copy_proxy_configuration))
            }
        }
    }
}

internal fun tcptunGoProxyConfigurationJson(
    proxyAddress: String,
    username: String,
    password: String,
): String {
    val address = proxyAddress.trim()
    require(address.isNotEmpty()) { "proxy address is required" }
    val networks = { JSONArray().apply { AndroidTunNetworks.forEach(::put) } }
    val inbound = JSONObject()
        .put("tag", "local")
        .put("type", "mixed")
        .put("address", JSONArray().put(RuntimeSettingsRepository.defaultLocalSocksConnectAddress()))
        .put("network", networks())
    val outbound = JSONObject()
        .put("tag", "proxy")
        .put("type", "socks5")
        .put("address", JSONArray().put(address))
        .put("network", networks())
        .put("username", username)
        .put("password", password)
    return JSONObject()
        .put("log", JSONObject().put("level", DefaultLogLevel))
        .put("inbounds", JSONArray().put(inbound))
        .put("outbounds", JSONArray().put(outbound))
        .put(
            "route",
            JSONObject()
                .put("default_outbound", "proxy")
                .put("rules", JSONArray()),
        )
        .put("dns", JSONObject())
        .toString(2)
}

private fun copyProxyConfiguration(
    context: Context,
    label: String,
    text: String,
    sensitive: Boolean,
): Boolean = runRecoverableCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return@runRecoverableCatching false
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    true
}.getOrDefault(false)

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
