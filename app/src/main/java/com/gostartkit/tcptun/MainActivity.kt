package com.tcptun.client

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TetheringManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tcptun.client.ui.theme.TcpTunTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val SnackbarAutoDismissMillis = 6_000L
private const val SavedProfileIntentSequence = "profileIntentSequence"
private const val SavedPendingProfileUri = "pendingProfileUri"
/** Brief wait after requesting a monitor refresh so pulled UI can show updated values. */
internal const val PullRefreshSettleMillis = 350L
private const val PostNotificationsPermission = "android.permission.POST_NOTIFICATIONS"

private val VpnPlanCommandExceptionHandler = CoroutineExceptionHandler { _, error ->
    if (error.isFatalProcessError()) throw error
    runRecoverableCatching {
        TcptunState.appendLog("VPN command coroutine failed: ${failureDescription(error)}")
    }
}
private val VpnPlanCommandScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate + VpnPlanCommandExceptionHandler,
)
private val VpnPlanCommandGeneration = AtomicInteger()
private val VpnPlanCommandJob = AtomicReference<Job?>(null)
private val UiErrorMessages = Channel<String>(
    capacity = 32,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
private val ProcessProfileMutationMutex = Mutex()
private val ProcessRouteRuleMutationMutex = Mutex()
private val QrCodeGenerationMutex = Mutex()
private const val MaxProfileMutationAttempts = 4
private const val MaxProfileNameInputLength = 512
private const val MaxProfileHostInputLength = 2_048
private const val MaxProfileChoiceInputLength = 256
private const val MaxRealityKeyInputLength = 4_096
private const val MaxEchKeyInputLength = 4_096

private val PendingRunPlanSaver = Saver<ProfileRunPlan?, String>(
    save = { plan -> encodePendingRunPlan(plan) },
    restore = { encoded -> decodePendingRunPlan(encoded) },
)
private val PendingProfileSaver = Saver<AppConfig?, String>(
    save = { profile -> encodePendingProfile(profile) },
    restore = { encoded -> decodePendingProfile(encoded) },
)

private val CardShapeCompact = RoundedCornerShape(12.dp)
private val MenuShape = RoundedCornerShape(12.dp)
private val DialogShape = RoundedCornerShape(28.dp)
private val QrCardShape = RoundedCornerShape(20.dp)
private val ListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val ListItemSpacing = 8.dp

@Composable
private fun FieldChromeText(text: String) {
    // Labels, placeholders, and supporting copy must not change a field's
    // measured height when translations or runtime values are longer.
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun AppConfig.boundedForEditor(): AppConfig = copy(
    name = name.take(MaxProfileNameInputLength),
    serverHost = serverHost.take(MaxProfileHostInputLength),
    serverPort = serverPort.filter(Char::isDigit).take(5),
    protocol = protocol.take(MaxProfileChoiceInputLength),
    transport = transport.take(MaxProfileChoiceInputLength),
    token = token.take(MaxProfileUriLength),
    sni = sni.take(MaxProfileHostInputLength),
    path = path.take(MaxProfileUriLength),
    tunnelSecurity = tunnelSecurity.take(MaxProfileChoiceInputLength),
    flow = flow.take(MaxProfileChoiceInputLength),
    realityPublicKey = realityPublicKey.take(MaxRealityKeyInputLength),
    realityShortId = realityShortId.take(MaxProfileChoiceInputLength),
    realityFingerprint = realityFingerprint.take(MaxProfileChoiceInputLength),
    realitySpiderX = realitySpiderX.take(MaxProfileUriLength),
    echPublicName = echPublicName.take(MaxProfileHostInputLength),
    echPublicKey = echPublicKey.take(MaxEchKeyInputLength),
    echPorts = echPorts.take(MaxProfileChoiceInputLength),
    carrierMode = carrierMode.take(MaxProfileChoiceInputLength),
    carrierUdpMode = carrierUdpMode.take(MaxProfileChoiceInputLength),
    upstreamProtocol = upstreamProtocol.take(MaxProfileChoiceInputLength),
    rawConfigJson = rawConfigJson.take(MaxProfileImportLength),
)

private fun AppConfig.withoutResumableMux(): AppConfig = copy(
    muxResume = false,
    muxResumeTimeoutMillis = 0,
    muxResumeBufferSize = 0,
)

private fun AppConfig.withoutEch(): AppConfig = copy(
    echEnabled = false,
    echPublicName = "",
    echPublicKey = "",
    echPorts = "",
)

private fun reportUiError(message: String) {
    val displayMessage = message.trim().ifBlank { "Unknown error" }
    TcptunState.appendLog("UI error: $displayMessage")
    UiErrorMessages.trySend(displayMessage)
}

internal data class LocalIpInfo(
    val underlyingInterface: String = "",
    val underlyingIpv4: String = "",
    val underlyingGatewayIpv4: String = "",
    val underlyingIpv6: String = "",
    val vpnIpv4: String = "",
    val vpnIpv6: String = "",
    val hotspotInterface: String = "",
    val hotspotIpv4: String = "",
)

private data class NetworkLinkInfo(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties,
)

internal data class MixedListenerNetworkDisplay(
    val ipv4: String,
    val gatewayIpv4: String,
)

internal enum class ProxyAccessScope {
    NotRunning,
    LocalOnly,
    Hotspot,
    LocalNetwork,
    Unavailable,
}

internal data class ProxyAccessDisplay(
    val address: String,
    val scope: ProxyAccessScope,
)

private fun readLocalIpInfo(
    connectivity: ConnectivityManager,
    networks: Collection<Network>,
    tetheredInterfaceNames: Set<String>?,
): LocalIpInfo {
    val links = networks.mapNotNull { network ->
        val capabilities = runRecoverableCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            ?: return@mapNotNull null
        val linkProperties = runRecoverableCatching { connectivity.getLinkProperties(network) }.getOrNull()
            ?: return@mapNotNull null
        NetworkLinkInfo(network, capabilities, linkProperties)
    }
    val activeNetwork = runRecoverableCatching { connectivity.activeNetwork }.getOrNull()
    val underlyingCandidates = links.filter {
        it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
    val underlying = underlyingCandidates.firstOrNull { it.network == activeNetwork }
        ?: underlyingCandidates.maxByOrNull {
            underlyingNetworkScore(
                validated = it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ethernet = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
    val vpn = links.firstOrNull {
        it.network == activeNetwork && it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } ?: links.firstOrNull { it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    val hotspot = selectHotspotIpv4Address(
        addresses = readInterfaceIpv4Addresses(),
        tetheredInterfaceNames = tetheredInterfaceNames,
        excludedInterfaceNames = setOfNotNull(
            underlying?.linkProperties?.interfaceName,
            vpn?.linkProperties?.interfaceName,
        ),
    )
    return LocalIpInfo(
        underlyingInterface = underlying?.linkProperties?.interfaceName.orEmpty(),
        underlyingIpv4 = formatIpAddresses(underlying?.linkProperties, ipv6 = false),
        underlyingGatewayIpv4 = formatDefaultGatewayIpv4(underlying?.linkProperties),
        underlyingIpv6 = formatIpAddresses(underlying?.linkProperties, ipv6 = true),
        vpnIpv4 = formatIpAddresses(vpn?.linkProperties, ipv6 = false),
        vpnIpv6 = formatIpAddresses(vpn?.linkProperties, ipv6 = true),
        hotspotInterface = hotspot?.interfaceName.orEmpty(),
        hotspotIpv4 = hotspot?.address.orEmpty(),
    )
}

private fun readLocalIpInfoSafely(
    connectivity: ConnectivityManager,
    networks: Collection<Network>,
    tetheredInterfaceNames: Set<String>?,
): LocalIpInfo = runRecoverableCatching {
    readLocalIpInfo(connectivity, networks, tetheredInterfaceNames)
}.getOrDefault(LocalIpInfo())

private fun formatDefaultGatewayIpv4(linkProperties: LinkProperties?): String {
    return linkProperties?.routes.orEmpty()
        .asSequence()
        .filter { it.isDefaultRoute }
        .mapNotNull { route -> route.gateway?.takeIf { it is Inet4Address }?.hostAddress }
        .distinct()
        .joinToString("\n")
}

private fun formatIpAddresses(linkProperties: LinkProperties?, ipv6: Boolean): String {
    return linkProperties?.linkAddresses.orEmpty()
        .asSequence()
        .filter { linkAddress ->
            val address = linkAddress.address
            !address.isLoopbackAddress && if (ipv6) address is Inet6Address else address is Inet4Address
        }
        .map { linkAddress -> "${linkAddress.address.hostAddress}/${linkAddress.prefixLength}" }
        .distinct()
        .joinToString("\n")
}

internal fun mixedListenerNetworkDisplay(
    listenAddress: String,
    underlyingIpv4: String,
    underlyingGatewayIpv4: String,
    hotspotIpv4: String = "",
): MixedListenerNetworkDisplay {
    val listenHost = hostFromListenAddress(listenAddress)
    val networkIpv4 = underlyingIpv4.substringBefore('\n').substringBefore('/').trim()
    val networkGateway = underlyingGatewayIpv4.substringBefore('\n').trim()
    return when (listenHost) {
        "", "0.0.0.0", "::", "*" -> if (hotspotIpv4.isNotBlank()) {
            MixedListenerNetworkDisplay(hotspotIpv4, "")
        } else {
            MixedListenerNetworkDisplay(networkIpv4, networkGateway)
        }
        "127.0.0.1", "::1", "localhost" -> MixedListenerNetworkDisplay(listenHost, "")
        else -> MixedListenerNetworkDisplay(
            ipv4 = listenHost,
            gatewayIpv4 = networkGateway.takeIf { listenHost == networkIpv4 }.orEmpty(),
        )
    }
}

internal fun proxyAccessDisplay(
    listenAddress: String,
    hotspotIpv4: String,
    underlyingIpv4: String,
    proxyRunning: Boolean,
): ProxyAccessDisplay {
    val listenHost = hostFromListenAddress(listenAddress)
    val port = portFromListenAddress(listenAddress)
    val networkIpv4 = underlyingIpv4.substringBefore('\n').substringBefore('/').trim()
    if (!proxyRunning) {
        val configuredHost = when (listenHost) {
            "", "0.0.0.0", "::", "*" -> hotspotIpv4.ifBlank { networkIpv4 }
            else -> listenHost
        }
        return ProxyAccessDisplay(formatHostPort(configuredHost, port), ProxyAccessScope.NotRunning)
    }
    return when (listenHost) {
        "127.0.0.1", "::1", "localhost" -> ProxyAccessDisplay(
            address = formatHostPort(listenHost, port),
            scope = ProxyAccessScope.LocalOnly,
        )
        "", "0.0.0.0", "::", "*" -> when {
            hotspotIpv4.isNotBlank() -> ProxyAccessDisplay(
                formatHostPort(hotspotIpv4, port),
                ProxyAccessScope.Hotspot,
            )
            networkIpv4.isNotBlank() -> ProxyAccessDisplay(
                formatHostPort(networkIpv4, port),
                ProxyAccessScope.LocalNetwork,
            )
            else -> ProxyAccessDisplay("", ProxyAccessScope.Unavailable)
        }
        else -> ProxyAccessDisplay(
            address = formatHostPort(listenHost, port),
            scope = if (listenHost == hotspotIpv4 && hotspotIpv4.isNotBlank()) {
                ProxyAccessScope.Hotspot
            } else {
                ProxyAccessScope.LocalNetwork
            },
        )
    }
}

private fun hostFromListenAddress(address: String): String {
    val value = address.trim().substringBefore(',').trim()
    if (value.startsWith('[')) return value.substringAfter('[').substringBefore(']').trim()
    return when (value.count { it == ':' }) {
        0 -> value
        1 -> value.substringBeforeLast(':').trim()
        else -> value
    }
}

private fun portFromListenAddress(address: String): String {
    val value = address.trim().substringBefore(',').trim()
    if (value.startsWith('[')) return value.substringAfter(']').removePrefix(":").trim()
    return if (value.count { it == ':' } == 1) value.substringAfterLast(':').trim() else ""
}

private fun formatHostPort(host: String, port: String): String {
    if (host.isBlank() || port.isBlank()) return host
    return if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
}

@RequiresApi(36)
private fun registerTetheringInterfaceCallback(
    context: Context,
    onChanged: (Set<String>) -> Unit,
): () -> Unit {
    val manager = runRecoverableCatching { context.getSystemService(TetheringManager::class.java) }.getOrNull()
        ?: return {}
    val callback = object : TetheringManager.TetheringEventCallback {
        override fun onTetheredInterfacesChanged(interfaces: Set<android.net.TetheringInterface>) {
            runRecoverableCatching {
                val wifiInterfaces = interfaces
                    .filter { it.type == TetheringManager.TETHERING_WIFI }
                    .mapTo(linkedSetOf()) { it.`interface` }
                onChanged(wifiInterfaces)
            }.onFailure { error ->
                TcptunState.appendLog("tethering callback failed: ${failureDescription(error)}")
            }
        }
    }
    manager.registerTetheringEventCallback(context.mainExecutor, callback)
    return { manager.unregisterTetheringEventCallback(callback) }
}

internal data class LocalIpInfoController(
    val info: LocalIpInfo,
    val refresh: () -> Unit,
)

@Composable
internal fun rememberLocalIpInfo(context: Context): LocalIpInfoController {
    val connectivity = remember(context) {
        runRecoverableCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
    }
    if (connectivity == null) return LocalIpInfoController(LocalIpInfo(), refresh = {})
    val refreshHandle = remember { AtomicReference<(() -> Unit)?>(null) }
    val initialNetworks = listOfNotNull(runRecoverableCatching { connectivity.activeNetwork }.getOrNull())
    val initialTetheredInterfaces: Set<String>? = if (Build.VERSION.SDK_INT >= 36) emptySet() else null
    val info by produceState(
        initialValue = LocalIpInfo(),
        connectivity,
    ) {
        val observedNetworks = initialNetworks.toMutableSet()
        val observedNetworksLock = Any()
        var tetheredInterfaceNames: Set<String>? = initialTetheredInterfaces
        var refreshSequence = 0L
        fun scheduleRefresh(
            nextTetheredInterfaceNames: Set<String>? = tetheredInterfaceNames,
            updateNetworks: MutableSet<Network>.() -> Unit = {},
        ) {
            val (sequence, snapshot, tetheredSnapshot) = synchronized(observedNetworksLock) {
                observedNetworks.updateNetworks()
                tetheredInterfaceNames = nextTetheredInterfaceNames
                Triple(++refreshSequence, observedNetworks.toList(), tetheredInterfaceNames)
            }
            launch {
                val next = withContext(Dispatchers.IO) {
                    readLocalIpInfoSafely(connectivity, snapshot, tetheredSnapshot)
                }
                val isCurrent = synchronized(observedNetworksLock) { sequence == refreshSequence }
                if (isCurrent) value = next
            }
        }
        refreshHandle.set { scheduleRefresh() }
        scheduleRefresh()
        fun refresh(network: Network, available: Boolean) {
            scheduleRefresh {
                if (available) add(network) else remove(network)
            }
        }
        fun refreshDefaultNetwork(network: Network) {
            scheduleRefresh {
                if (runRecoverableCatching { connectivity.activeNetwork }.getOrNull() == network) add(network)
            }
        }
        fun runNetworkCallback(label: String, action: () -> Unit) {
            runRecoverableCatching(action).onFailure { error ->
                TcptunState.appendLog("UI network $label callback failed: ${failureDescription(error)}")
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runNetworkCallback("available") {
                refresh(network, available = true)
            }
            override fun onLost(network: Network) = runNetworkCallback("lost") {
                refresh(network, available = false)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                runNetworkCallback("capabilities changed") { refresh(network, available = true) }
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runNetworkCallback("link properties changed") { refresh(network, available = true) }
            }
        }
        val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runNetworkCallback("default available") {
                refreshDefaultNetwork(network)
            }
            override fun onLost(network: Network) = runNetworkCallback("default lost") {
                scheduleRefresh()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                runNetworkCallback("default capabilities changed") { refreshDefaultNetwork(network) }
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runNetworkCallback("default link properties changed") { refreshDefaultNetwork(network) }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val registered = runRecoverableCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess
        val defaultRegistered = runRecoverableCatching {
            connectivity.registerDefaultNetworkCallback(defaultNetworkCallback)
        }.isSuccess
        val unregisterTetheringCallback = if (Build.VERSION.SDK_INT >= 36) {
            runRecoverableCatching {
                registerTetheringInterfaceCallback(context) { wifiInterfaces ->
                    scheduleRefresh(nextTetheredInterfaceNames = wifiInterfaces)
                }
            }.getOrNull()
        } else {
            null
        }
        // Hotspot address updates on older APIs rely on pull-to-refresh instead of a timer.
        awaitDispose {
            refreshHandle.set(null)
            if (registered) runRecoverableCatching { connectivity.unregisterNetworkCallback(callback) }
            if (defaultRegistered) runRecoverableCatching { connectivity.unregisterNetworkCallback(defaultNetworkCallback) }
            unregisterTetheringCallback?.let { unregister -> runRecoverableCatching(unregister) }
        }
    }
    return LocalIpInfoController(
        info = info,
        refresh = { refreshHandle.get()?.invoke() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullRefreshContainer(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    onRefresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    TcptunState.appendLog("UI refresh failed: ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier,
    ) {
        content()
    }
}

internal suspend fun refreshRunningDiagnostics() {
    if (TcptunState.status != "Running") return
    TcptunVpnService.requestUiVisibleHealthCheck()
    delay(PullRefreshSettleMillis)
}

class MainActivity : ComponentActivity() {
    private var profileIntentSequence = 0L
    private var pendingProfileUri by mutableStateOf<PendingProfileUri?>(null)
    private var uiVisibilityLease: UiVisibilityLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TcpTun)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleProfileIntent(intent)
        } else {
            profileIntentSequence = savedInstanceState.getLong(SavedProfileIntentSequence, 0L)
            savedInstanceState.getString(SavedPendingProfileUri)
                ?.takeIf { it.length <= MaxProfileUriLength }
                ?.let { value -> pendingProfileUri = PendingProfileUri(profileIntentSequence, value) }
        }
        enableEdgeToEdge()
        setContent {
            TcpTunTheme {
                TcptunScreen(
                    pendingProfileUri = pendingProfileUri,
                    onProfileUriConsumed = { sequence ->
                        if (pendingProfileUri?.sequence == sequence) pendingProfileUri = null
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (uiVisibilityLease == null) {
            uiVisibilityLease = TcptunState.acquireUiVisibility()
        }
        if (TcptunState.status == "Running") {
            TcptunVpnService.requestUiVisibleHealthCheck()
        }
    }

    override fun onStop() {
        releaseUiVisibility()
        super.onStop()
    }

    override fun onDestroy() {
        // Defensive for non-standard framework/test teardown that skips onStop.
        releaseUiVisibility()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(SavedProfileIntentSequence, profileIntentSequence)
        pendingProfileUri?.value?.let { value -> outState.putString(SavedPendingProfileUri, value) }
        super.onSaveInstanceState(outState)
    }

    private fun handleProfileIntent(intent: Intent?) {
        val value = profileUriFromIntent(intent) ?: return
        pendingProfileUri = PendingProfileUri(++profileIntentSequence, value)
    }

    private fun releaseUiVisibility() {
        uiVisibilityLease?.close()
        uiVisibilityLease = null
    }
}

@Composable
internal fun TcptunScreen(
    pendingProfileUri: PendingProfileUri? = null,
    onProfileUriConsumed: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val emptyClipboard = stringResource(R.string.empty_clipboard)
    val invalidClipboard = stringResource(R.string.invalid_clipboard_data)
    val undoLabel = stringResource(R.string.undo)
    var storedState by remember { mutableStateOf<ProfilesState?>(null) }
    var pendingDeepLinkProfile by rememberSaveable(stateSaver = PendingProfileSaver) {
        mutableStateOf<AppConfig?>(null)
    }
    var pendingConfig by rememberSaveable(stateSaver = PendingRunPlanSaver) {
        mutableStateOf<ProfileRunPlan?>(null)
    }
    var pendingNotificationConfig by rememberSaveable(stateSaver = PendingRunPlanSaver) {
        mutableStateOf<ProfileRunPlan?>(null)
    }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showIpInformation by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFlowAnalysis by remember { mutableStateOf(false) }
    var showRouteManagement by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var scannerSessionGeneration by remember { mutableIntStateOf(0) }
    var scannerImportJob by remember { mutableStateOf<Job?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var profileQrCode by remember { mutableStateOf<AppConfig?>(null) }
    var tcpingTargetIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarHostState) {
        for (message in UiErrorMessages) {
            snackbarHostState.showDismissibleSnackbar(message)
        }
    }
    val screenScope = rememberCoroutineScope()
    val profileMutationMutex = ProcessProfileMutationMutex
    val profileReloadGeneration = remember { AtomicInteger() }
    val profileListState = rememberLazyListState()
    var draggedProfileId by remember { mutableStateOf<String?>(null) }
    var draggedProfileOffset by remember { mutableStateOf(0f) }
    var profilesBeforeDrag by remember { mutableStateOf<List<AppConfig>?>(null) }
    var profileReorderScrollJob by remember { mutableStateOf<Job?>(null) }
    val profileReorderScrollStep = with(LocalDensity.current) { 24.dp.toPx() }
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    LaunchedEffect(context) {
        val generation = profileReloadGeneration.incrementAndGet()
        val loaded = profileMutationMutex.withLock {
            withContext(Dispatchers.IO) { ProfileStore.load(context) }
        }
        if (generation == profileReloadGeneration.get()) storedState = loaded
    }
    LaunchedEffect(vpnState.status) {
        if (vpnState.status == "Stopped" || vpnState.status == "Error") {
            delay(100)
            val generation = profileReloadGeneration.incrementAndGet()
            val loaded = profileMutationMutex.withLock {
                withContext(Dispatchers.IO) { ProfileStore.load(context) }
            }
            if (generation == profileReloadGeneration.get()) storedState = loaded
        }
    }
    LaunchedEffect(vpnState.profileStateRevision) {
        if (vpnState.profileStateRevision > 0) {
            val generation = profileReloadGeneration.incrementAndGet()
            val loaded = profileMutationMutex.withLock {
                withContext(Dispatchers.IO) { ProfileStore.load(context) }
            }
            if (generation == profileReloadGeneration.get()) storedState = loaded
        }
    }
    val state = storedState
    if (state == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    fun requestVpnStart(plan: ProfileRunPlan) {
        startVpn(context, plan)
    }
    fun failPendingVpnStart(plan: ProfileRunPlan, message: String) {
        reportUiError(message)
        VpnPlanCommandScope.launch {
            runRecoverableCatching {
                rollbackInitialStartAfterDispatchFailure(context.applicationContext, plan)
            }.onFailure { rollbackError ->
                TcptunState.appendLog(
                    "failed VPN start rollback failed: ${failureDescription(rollbackError)}",
                )
            }
        }
    }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val plan = pendingConfig
        pendingConfig = null
        if (plan != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                requestVpnStart(plan)
            } else {
                failPendingVpnStart(plan, resources.getString(R.string.start_failed))
            }
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val plan = pendingNotificationConfig ?: return@rememberLauncherForActivityResult
        pendingNotificationConfig = null
        if (!granted) {
            TcptunState.appendLog("notification permission denied; foreground notification may be hidden")
        }
        runRecoverableCatching { VpnService.prepare(context) }.fold(
            onSuccess = { prepare ->
                if (prepare == null) {
                    requestVpnStart(plan)
                } else {
                    pendingConfig = plan
                    runRecoverableCatching { vpnLauncher.launch(prepare) }.onFailure { error ->
                        pendingConfig = null
                        failPendingVpnStart(
                            plan,
                            error.message ?: resources.getString(R.string.start_failed),
                        )
                    }
                }
            },
            onFailure = { error ->
                failPendingVpnStart(
                    plan,
                    error.message ?: resources.getString(R.string.start_failed),
                )
            },
        )
    }
    fun requireProfileMutationAllowed() {
        check(!isVpnTransitionStatus(TcptunState.status)) {
            resources.getString(R.string.profile_save_failed)
        }
    }
    suspend fun commitProfileMutationLocked(
        transform: (ProfilesState) -> ProfilesState,
        validate: suspend (ProfilesState, ProfilesState) -> Unit = { _, _ -> },
    ): Pair<ProfilesState, ProfileStoreSnapshot> {
        val generation = profileReloadGeneration.incrementAndGet()
        return withContext(NonCancellable) {
            repeat(MaxProfileMutationAttempts) {
                requireProfileMutationAllowed()
                val snapshot = withContext(Dispatchers.IO) { ProfileStore.snapshot(context) }
                val next = withContext(Dispatchers.Default) { transform(snapshot.state) }
                validate(snapshot.state, next)
                requireProfileMutationAllowed()
                val saved = withContext(Dispatchers.IO) {
                    ProfileStore.saveIfCurrent(context, snapshot, next).getOrThrow()
                }
                if (saved != null) {
                    if (generation == profileReloadGeneration.get()) storedState = saved.state
                    TcptunState.notifyProfileStateChanged()
                    return@withContext snapshot.state to saved
                }
            }
            throw IllegalStateException("profile state changed repeatedly; please retry")
        }
    }

    suspend fun saveProfileMutation(
        transform: (ProfilesState) -> ProfilesState,
    ): ProfilesState? {
        return try {
            profileMutationMutex.withLock {
                commitProfileMutationLocked(transform).second.state
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiError(error.message ?: resources.getString(R.string.profile_save_failed))
            null
        }
    }

    suspend fun rollbackCommittedProfileMutation(
        committed: ProfileStoreSnapshot,
        previous: ProfilesState,
    ): Boolean {
        val generation = profileReloadGeneration.incrementAndGet()
        return withContext(NonCancellable) {
            repeat(MaxProfileMutationAttempts) {
                val snapshot = withContext(Dispatchers.IO) { ProfileStore.snapshot(context) }
                val currentState = snapshot.requireAuthoritativeState()
                val rollback = rollbackProfileStateIfStillCommitted(
                    current = currentState,
                    currentRevision = snapshot.mutationRevision,
                    committed = committed.state,
                    committedRevision = committed.mutationRevision,
                    previous = previous,
                ) ?: run {
                    TcptunState.appendLog(
                        "profile rollback skipped because a newer profile state is already stored",
                    )
                    return@withContext false
                }
                val restored = withContext(Dispatchers.IO) {
                    ProfileStore.saveIfCurrent(context, snapshot, rollback).getOrThrow()
                }
                if (restored != null) {
                    if (generation == profileReloadGeneration.get()) storedState = restored.state
                    TcptunState.notifyProfileStateChanged()
                    TcptunState.appendLog("profile state rolled back after VPN command dispatch failed")
                    return@withContext true
                }
            }
            throw IllegalStateException("profile state changed repeatedly while rolling back")
        }
    }

    suspend fun decodeValidatedProfile(raw: String): AppConfig = withContext(Dispatchers.Default) {
        ProfileUriCodec.decode(raw).getOrThrow().also(::validateImportedProfile)
    }

    suspend fun storeValidatedProfile(profile: AppConfig): Pair<AppConfig, Boolean> =
        profileMutationMutex.withLock {
            val identity = withContext(Dispatchers.Default) {
                profileConnectionIdentity(profile)
                    ?: throw IllegalArgumentException("profile identity could not be created")
            }
            var storedProfile = profile
            var added = false
            commitProfileMutationLocked(transform = { current ->
                current.profiles.firstOrNull { candidate ->
                    profileConnectionIdentity(candidate) == identity
                }?.let { existing ->
                    storedProfile = existing
                    added = false
                    current
                } ?: current.copy(profiles = current.profiles + profile).also {
                    storedProfile = profile
                    added = true
                }
            })
            storedProfile to added
        }

    fun openQrScanner() {
        if (isVpnTransitionStatus(vpnState.status)) return
        scannerImportJob?.cancel()
        scannerImportJob = null
        scannerSessionGeneration += 1
        showQrScanner = true
    }

    fun closeQrScanner() {
        scannerSessionGeneration += 1
        scannerImportJob?.cancel()
        scannerImportJob = null
        showQrScanner = false
    }

    LaunchedEffect(pendingProfileUri?.sequence) {
        val pending = pendingProfileUri ?: return@LaunchedEffect
        try {
            val profile = decodeValidatedProfile(pending.value)
            val isDeepLink = withContext(Dispatchers.Default) {
                ProfileDeepLinkCodec.isSupportedLink(pending.value)
            }
            if (isDeepLink) {
                pendingDeepLinkProfile = profile
            } else {
                val (storedProfile, added) = storeValidatedProfile(profile)
                if (added) {
                    snackbarHostState.showDismissibleSnackbar(
                        resources.getString(R.string.profile_imported, storedProfile.name),
                    )
                } else {
                    snackbarHostState.showDismissibleSnackbar(
                        resources.getString(R.string.profile_already_exists, storedProfile.name),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snackbarHostState.showDismissibleSnackbar(resources.getString(R.string.invalid_profile_link))
        } finally {
            onProfileUriConsumed(pending.sequence)
        }
    }

    fun importFromClipboard() {
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            try {
                val link = withContext(Dispatchers.IO) { clipboardText(context).getOrThrow() }.trim()
                if (link.isBlank()) {
                    reportUiError(emptyClipboard)
                    return@launch
                }
                val profile = decodeValidatedProfile(link)
                storeValidatedProfile(profile)
                withContext(Dispatchers.IO) { clearClipboardText(context, link) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportUiError(invalidClipboard)
            }
        }
    }

    fun importScannedProfile(link: String, onComplete: (Boolean) -> Unit) {
        val generation = scannerSessionGeneration
        scannerImportJob?.cancel()
        scannerImportJob = screenScope.launch {
            try {
                val profile = decodeValidatedProfile(link.trim())
                if (generation != scannerSessionGeneration || !showQrScanner) return@launch
                val (storedProfile, added) = storeValidatedProfile(profile)
                if (generation != scannerSessionGeneration || !showQrScanner) return@launch
                onComplete(true)
                showQrScanner = false
                scannerSessionGeneration += 1
                scannerImportJob = null
                val message = if (added) {
                    resources.getString(R.string.profile_imported, storedProfile.name)
                } else {
                    resources.getString(R.string.profile_already_exists, storedProfile.name)
                }
                snackbarHostState.showDismissibleSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == scannerSessionGeneration && showQrScanner) onComplete(false)
            } finally {
                if (generation == scannerSessionGeneration) scannerImportJob = null
            }
        }
    }

    fun launchPlan(plan: ProfileRunPlan) {
        TcptunState.clearLogs()
        if (needsNotificationPermission(context)) {
            pendingNotificationConfig = plan
            runRecoverableCatching { notificationLauncher.launch(PostNotificationsPermission) }
                .onFailure { error ->
                    pendingNotificationConfig = null
                    failPendingVpnStart(
                        plan,
                        error.message ?: resources.getString(R.string.start_failed),
                    )
                }
            return
        }
        runRecoverableCatching { VpnService.prepare(context) }.fold(
            onSuccess = { prepare ->
                if (prepare == null) {
                    requestVpnStart(plan)
                } else {
                    pendingConfig = plan
                    runRecoverableCatching { vpnLauncher.launch(prepare) }.onFailure { error ->
                        pendingConfig = null
                        failPendingVpnStart(
                            plan,
                            error.message ?: resources.getString(R.string.start_failed),
                        )
                    }
                }
            },
            onFailure = { error ->
                failPendingVpnStart(
                    plan,
                    error.message ?: resources.getString(R.string.start_failed),
                )
            },
        )
    }

    suspend fun applyRunningMutation(
        shouldApplyRuntime: (ProfilesState) -> Boolean = { true },
        transform: (ProfilesState) -> ProfilesState,
    ): Boolean {
        return try {
            var pendingInteractiveStart: ProfileRunPlan? = null
            val mutationApplied = profileMutationMutex.withLock {
                withContext(NonCancellable) {
                    var applyRuntime = false
                    var intendedOutboundUpdate = false
                    var plan: ProfileRunPlan? = null
                    val (previousState, committedSnapshot) = commitProfileMutationLocked(
                        transform = transform,
                        validate = { current, nextState ->
                            applyRuntime = shouldApplyRuntime(current)
                            intendedOutboundUpdate =
                                TcptunState.status == "Running" && current.activeIds.isNotEmpty()
                            plan = if (!applyRuntime || nextState.activeIds.isEmpty()) {
                                null
                            } else {
                                withContext(Dispatchers.Default) { nextState.runPlan() }
                            }
                            plan?.let { candidatePlan ->
                                // Profile activation and route commits use the same lock order.
                                // Validate the exact generated Binder payload before activeIds
                                // can become authoritative, including first start from Stopped.
                                ProcessRouteRuleMutationMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        TcptunVpnService.preflightStartPayload(
                                            context = context,
                                            sourcePlan = candidatePlan,
                                            managedRouteRules = RouteRuleStore
                                                .loadAuthoritative(context)
                                                .getOrThrow(),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (!applyRuntime) return@withContext true
                    val committedPlan = plan
                    if (committedPlan == null) {
                        if (!stopVpn(context.applicationContext)) {
                            rollbackCommittedProfileMutation(committedSnapshot, previousState)
                            return@withContext false
                        }
                    } else {
                        TcptunState.clearTcping()
                        if (intendedOutboundUpdate) {
                            if (TcptunState.status == "Running") {
                                // Dispatch before releasing the mutation mutex. A later mutation may
                                // use this committed state as its rollback baseline only after the
                                // service has accepted the corresponding command.
                                val dispatched = updateVpnOutbounds(
                                    context = context.applicationContext,
                                    plan = committedPlan,
                                )
                                if (!dispatched) {
                                    rollbackCommittedProfileMutation(committedSnapshot, previousState)
                                    return@withContext false
                                }
                            } else {
                                TcptunState.appendLog(
                                    "profile runtime update skipped: VPN state changed to ${TcptunState.status}",
                                )
                            }
                        } else {
                            pendingInteractiveStart = committedPlan
                        }
                    }
                    true
                }
            }
            if (!mutationApplied) return false
            pendingInteractiveStart?.let(::launchPlan)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiError(error.message ?: resources.getString(R.string.profile_save_failed))
            showLogs = true
            false
        }
    }

    fun toggleProfile(profile: AppConfig) {
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            applyRunningMutation { current ->
                val nextActiveIds = nextActiveProfileIds(
                    activeIds = current.activeIds,
                    profileId = profile.id,
                    vpnStatus = TcptunState.status,
                )
                current.copy(activeIds = nextActiveIds)
            }
        }
    }

    fun deleteProfile(profile: AppConfig) {
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            var profileIndex = -1
            var deletedProfile: AppConfig? = null
            var wasActive = false
            val deleteTransform: (ProfilesState) -> ProfilesState = { current ->
                profileIndex = current.profiles.indexOfFirst { it.id == profile.id }
                deletedProfile = current.profiles.getOrNull(profileIndex)
                current.copy(
                    profiles = current.profiles.filterNot { it.id == profile.id },
                    activeIds = current.activeIds - profile.id,
                )
            }
            val deleted = applyRunningMutation(
                shouldApplyRuntime = { current ->
                    (profile.id in current.activeIds).also { active -> wasActive = active }
                },
                transform = deleteTransform,
            )
            val removed = deletedProfile
            if (!deleted || removed == null || profileIndex < 0) return@launch
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showDismissibleSnackbar(
                message = resources.getString(R.string.profile_deleted, removed.name),
                actionLabel = undoLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val restoreTransform: (ProfilesState) -> ProfilesState = { current ->
                    if (current.profiles.any { it.id == removed.id }) {
                        current
                    } else {
                        val restored = current.profiles.toMutableList()
                        restored.add(profileIndex.coerceIn(0, restored.size), removed)
                        current.copy(
                            profiles = restored,
                            activeIds = if (wasActive) current.activeIds + removed.id else current.activeIds,
                        )
                    }
                }
                applyRunningMutation(
                    shouldApplyRuntime = { wasActive },
                    transform = restoreTransform,
                )
            }
        }
    }

    fun startProfileDrag(profileId: String) {
        if (isVpnTransitionStatus(vpnState.status)) return
        draggedProfileId = profileId
        draggedProfileOffset = 0f
        profilesBeforeDrag = state.profiles
    }

    fun dragProfile(profileId: String, deltaY: Float) {
        if (draggedProfileId != profileId || !deltaY.isFinite()) return
        draggedProfileOffset += deltaY
        val visibleItems = profileListState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.firstOrNull { it.key == profileId } ?: return
        val draggedCenter = draggedItem.offset + draggedProfileOffset + draggedItem.size / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            item.key != profileId &&
                state.profiles.any { it.id == item.key } &&
                draggedCenter >= item.offset &&
                draggedCenter <= item.offset + item.size
        }
        if (targetItem != null) {
            val fromIndex = state.profiles.indexOfFirst { it.id == profileId }
            val toIndex = state.profiles.indexOfFirst { it.id == targetItem.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val reordered = state.profiles.toMutableList().also { profiles ->
                    profiles.add(toIndex, profiles.removeAt(fromIndex))
                }
                storedState = state.copy(profiles = reordered)
                draggedProfileOffset += draggedItem.offset - targetItem.offset
            }
        }

        if (profileReorderScrollJob?.isActive != true) {
            profileReorderScrollJob = screenScope.launch {
                while (draggedProfileId == profileId) {
                    val layoutInfo = profileListState.layoutInfo
                    val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == profileId } ?: break
                    val currentTop = currentItem.offset + draggedProfileOffset
                    val currentBottom = currentTop + currentItem.size
                    val scrollDelta = when {
                        currentTop < layoutInfo.viewportStartOffset -> -profileReorderScrollStep
                        currentBottom > layoutInfo.viewportEndOffset -> profileReorderScrollStep
                        else -> break
                    }
                    val consumed = profileListState.scrollBy(scrollDelta)
                    if (consumed == 0f) break
                    if (draggedProfileId == profileId) draggedProfileOffset += consumed
                    delay(16)
                }
            }
        }
    }

    fun finishProfileDrag() {
        val original = profilesBeforeDrag
        val reordered = state.profiles
        profileReorderScrollJob?.cancel()
        profileReorderScrollJob = null
        draggedProfileId = null
        draggedProfileOffset = 0f
        profilesBeforeDrag = null
        if (original != null && original.map { it.id } != reordered.map { it.id }) {
            val reorderedIds = reordered.map(AppConfig::id)
            screenScope.launch {
                val saved = saveProfileMutation { current ->
                    val byId = current.profiles.associateBy(AppConfig::id)
                    val ordered = buildList {
                        reorderedIds.mapNotNullTo(this) { byId[it] }
                        current.profiles.filterTo(this) { it.id !in reorderedIds }
                    }
                    current.copy(profiles = ordered)
                }
                if (saved == null) {
                    val current = storedState ?: return@launch
                    val byId = current.profiles.associateBy(AppConfig::id)
                    val originalIds = original.map(AppConfig::id)
                    storedState = current.copy(
                        profiles = buildList {
                            originalIds.mapNotNullTo(this) { byId[it] }
                            current.profiles.filterTo(this) { it.id !in originalIds }
                        },
                    )
                }
            }
        }
    }

    val editing = editingProfile
    val showingMainList = !showQrScanner &&
        !showIpInformation &&
        !showDiagnostics &&
        !showSettings &&
        !showFlowAnalysis &&
        !showRouteManagement &&
        editing == null
    if (showQrScanner) {
        QrScannerPage(
            onBack = ::closeQrScanner,
            onProfileScanned = ::importScannedProfile,
        )
    } else if (showIpInformation) {
        IpInformationPage(onBack = { showIpInformation = false })
    } else if (showDiagnostics) {
        DiagnosticsPage(
            onBack = { showDiagnostics = false },
            onShowLogs = { showLogs = true },
        )
    } else if (showSettings) {
        SettingsPage(
            onBack = { showSettings = false },
        )
    } else if (showFlowAnalysis) {
        FlowAnalysisPage(onBack = { showFlowAnalysis = false })
    } else if (showRouteManagement) {
        RouteManagementPage(
            onBack = { showRouteManagement = false },
        )
    } else if (editing == null) {
        val listIpInfo = rememberLocalIpInfo(context)
        val configuredListenAddress = TcptunVpnService.localSocksListenAddr(
            rememberUiRuntimeSettings(context) ?: RuntimeSettings(),
        )
        val effectiveListenAddress = vpnState.diagnostics.bridgeListen
            .takeIf { vpnState.status == "Running" }
            .orEmpty()
            .ifBlank { configuredListenAddress }
        val proxyAccess = proxyAccessDisplay(
            listenAddress = effectiveListenAddress,
            hotspotIpv4 = listIpInfo.info.hotspotIpv4,
            underlyingIpv4 = listIpInfo.info.underlyingIpv4,
            proxyRunning = vpnState.status == "Running",
        )
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(
                    title = stringResource(R.string.profiles_title),
                    onDiagnostics = { showDiagnostics = true },
                    onRouteManagement = { showRouteManagement = true },
                    onFlowAnalysis = { showFlowAnalysis = true },
                    onSettings = { showSettings = true },
                )
            },
            snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
            floatingActionButton = {
                MainActionsFab(
                    enabled = !isVpnTransitionStatus(vpnState.status),
                    onImport = ::importFromClipboard,
                    onScan = ::openQrScanner,
                )
            },
            bottomBar = {
                val tcpingEnabled = canStartTcping(
                    status = vpnState.status,
                    activeProfileCount = state.activeProfiles.size,
                    connectionsReady = vpnState.connectionsReady,
                )
                BottomStatus(
                    status = vpnState.status,
                    error = vpnState.lastError,
                    tcping = vpnState.tcping,
                    hasProfiles = state.profiles.isNotEmpty(),
                    connectionsReady = vpnState.connectionsReady,
                    tcpingEnabled = tcpingEnabled,
                    onClick = {
                        if (!tcpingEnabled || vpnState.tcping.running) return@BottomStatus
                        val tcpingTarget = TCPING_TARGETS.getOrNull(tcpingTargetIndex)
                            ?: TCPING_TARGETS.firstOrNull()
                            ?: return@BottomStatus
                        tcpingTargetIndex = (tcpingTargetIndex + 1) % TCPING_TARGETS.size
                        val requestId = TcptunState.beginTcping(
                            targetLabel = tcpingTarget.label,
                            total = state.activeProfiles.size,
                        )
                        runRecoverableCatching {
                            context.startService(
                                TcptunVpnService.tcpingOutboundsIntent(
                                    context = context,
                                    requestId = requestId,
                                    targetLabel = tcpingTarget.label,
                                    host = tcpingTarget.host,
                                    port = tcpingTarget.port,
                                ),
                            )
                        }.onFailure { err ->
                            TcptunState.failTcping(requestId, err.message ?: resources.getString(R.string.tcping_failed_fallback))
                        }
                    },
                )
            },
        ) { padding ->
            PullRefreshContainer(
                onRefresh = {
                    val generation = profileReloadGeneration.incrementAndGet()
                    val loaded = profileMutationMutex.withLock {
                        withContext(Dispatchers.IO) { ProfileStore.load(context) }
                    }
                    if (generation == profileReloadGeneration.get()) storedState = loaded
                    listIpInfo.refresh()
                    refreshRunningDiagnostics()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
            LazyColumn(
                state = profileListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
            ) {
                item(key = "mixed-listener-network-header") {
                    ProfileListHeader(
                        proxyAccess = proxyAccess,
                        onIpClick = { showIpInformation = true },
                    )
                }
                items(state.profiles, key = { it.id }) { profile ->
                    val dragging = draggedProfileId == profile.id
                    ProfileRow(
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) draggedProfileOffset else 0f
                                shadowElevation = if (dragging) 8.dp.toPx() else 0f
                                shape = CardShape
                            },
                        profile = profile,
                        running = profile.id in state.activeIds && isVpnActiveStatus(vpnState.status),
                        health = vpnState.profileHealth[profile.id],
                        enabled = !isVpnTransitionStatus(vpnState.status),
                        onClick = { toggleProfile(profile) },
                        onShare = { uri -> shareProfile(context, uri) },
                        onShowQrCode = { profileQrCode = profile },
                        onEdit = { editingProfile = profile },
                        dragging = dragging,
                        onDragStart = { startProfileDrag(profile.id) },
                        onDrag = { deltaY -> dragProfile(profile.id, deltaY) },
                        onDragEnd = ::finishProfileDrag,
                        onDeleteRequest = { deleteProfile(profile) },
                    )
                }
                if (state.profiles.isEmpty()) {
                    item {
                        EmptyState(
                            enabled = !isVpnTransitionStatus(vpnState.status),
                            onAdd = {
                                editingProfile = AppConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = "proxy",
                                )
                            },
                        )
                    }
                }
            }
            }
        }
    } else {
        EditProfilePage(
            initial = editing,
            onBack = { editingProfile = null },
            onSave = { updated ->
                screenScope.launch {
                    val transform: (ProfilesState) -> ProfilesState = { current ->
                        val profiles = current.profiles.toMutableList()
                        val index = profiles.indexOfFirst { it.id == updated.id }
                        if (index >= 0) profiles[index] = updated else profiles.add(updated)
                        current.copy(profiles = profiles)
                    }
                    val saved = applyRunningMutation(
                        shouldApplyRuntime = { current ->
                            updated.id in current.activeIds && TcptunState.status == "Running"
                        },
                        transform = transform,
                    )
                    if (saved) editingProfile = null
                }
            },
        )
    }

    // Sub-pages replace the main Scaffold, so keep the process-wide error host
    // visible there as an overlay as well.
    if (!showingMainList) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AutoDismissSnackbarHost(snackbarHostState)
        }
    }

    if (showLogs) {
        LogsDialog(onDismiss = { showLogs = false })
    }

    profileQrCode?.let { profile ->
        ProfileQrCodeDialog(
            profile = profile,
            onDismiss = { profileQrCode = null },
        )
    }

    pendingDeepLinkProfile?.let { profile ->
        ConfirmProfileImportDialog(
            profile = profile,
            enabled = !isVpnTransitionStatus(vpnState.status),
            onConfirm = {
                pendingDeepLinkProfile = null
                screenScope.launch {
                    try {
                        val (storedProfile, added) = storeValidatedProfile(profile)
                            val message = if (added) {
                                resources.getString(R.string.profile_imported, storedProfile.name)
                            } else {
                                resources.getString(R.string.profile_already_exists, storedProfile.name)
                            }
                        snackbarHostState.showDismissibleSnackbar(message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        snackbarHostState.showDismissibleSnackbar(
                            resources.getString(R.string.invalid_profile_link),
                        )
                    }
                }
            },
            onDismiss = { pendingDeepLinkProfile = null },
        )
    }

}

@Composable
private fun ProfileListHeader(
    proxyAccess: ProxyAccessDisplay,
    onIpClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val addressLabel = when (proxyAccess.scope) {
        ProxyAccessScope.NotRunning -> stringResource(R.string.proxy_access_address)
        ProxyAccessScope.LocalOnly -> stringResource(R.string.proxy_local_only_address)
        ProxyAccessScope.Hotspot -> stringResource(R.string.proxy_hotspot_address)
        ProxyAccessScope.LocalNetwork -> stringResource(R.string.proxy_lan_address)
        ProxyAccessScope.Unavailable -> stringResource(R.string.proxy_access_address)
    }
    val accessStatus = when (proxyAccess.scope) {
        ProxyAccessScope.NotRunning -> stringResource(R.string.proxy_not_running)
        ProxyAccessScope.LocalOnly -> stringResource(R.string.proxy_hotspot_unavailable)
        ProxyAccessScope.Hotspot -> stringResource(R.string.proxy_hotspot_available)
        ProxyAccessScope.LocalNetwork -> stringResource(R.string.proxy_lan_available)
        ProxyAccessScope.Unavailable -> stringResource(R.string.proxy_no_reachable_address)
    }
    val statusColor = when (proxyAccess.scope) {
        ProxyAccessScope.Hotspot, ProxyAccessScope.LocalNetwork -> colors.primary
        ProxyAccessScope.NotRunning, ProxyAccessScope.LocalOnly, ProxyAccessScope.Unavailable -> colors.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = colors.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.view_ip_information),
                    onClick = onIpClick,
                )
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Lan,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = proxyAccess.address.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = addressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = accessStatus,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.proxy_access_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoDismissSnackbarHost(hostState: SnackbarHostState) {
    val snackbarData = hostState.currentSnackbarData
    LaunchedEffect(snackbarData) {
        if (snackbarData != null && snackbarData.visuals.actionLabel == null) {
            delay(SnackbarAutoDismissMillis)
            if (hostState.currentSnackbarData === snackbarData) snackbarData.dismiss()
        }
    }
    SnackbarHost(hostState)
}

private suspend fun SnackbarHostState.showDismissibleSnackbar(
    message: String,
    actionLabel: String? = null,
): SnackbarResult = showSnackbar(
    message = message,
    actionLabel = actionLabel,
    withDismissAction = true,
    duration = SnackbarDuration.Indefinite,
)

@Composable
private fun ConfirmProfileImportDialog(
    profile: AppConfig,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = rememberProfilePresentation(profile)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_profile_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(presentation.maskedAddress, style = MaterialTheme.typography.bodyLarge)
                Text(
                    presentation.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.profile_import_confirmation_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.add_profile))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    onDiagnostics: () -> Unit,
    onRouteManagement: () -> Unit,
    onFlowAnalysis: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.onBackground,
            actionIconContentColor = colors.onSurfaceVariant,
        ),
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = MenuShape,
                    containerColor = colors.surfaceContainer,
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Speed, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.diagnostics)) },
                        onClick = {
                            menuExpanded = false
                            onDiagnostics()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Hub, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.flow_analysis)) },
                        onClick = {
                            menuExpanded = false
                            onFlowAnalysis()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.route_management)) },
                        onClick = {
                            menuExpanded = false
                            onRouteManagement()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Tune, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.settings)) },
                        onClick = {
                            menuExpanded = false
                            onSettings()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    )
}

@Composable
private fun MainActionsFab(
    enabled: Boolean,
    onImport: () -> Unit,
    onScan: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box(contentAlignment = Alignment.BottomEnd) {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape = MenuShape,
            containerColor = colors.surfaceContainer,
        ) {
            DropdownMenuItem(
                enabled = enabled,
                leadingIcon = {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                },
                text = { Text(stringResource(R.string.scan_qr_code)) },
                onClick = {
                    menuExpanded = false
                    onScan()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
            DropdownMenuItem(
                enabled = enabled,
                leadingIcon = {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                },
                text = { Text(stringResource(R.string.import_from_clipboard)) },
                onClick = {
                    menuExpanded = false
                    onImport()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
        }
        FloatingActionButton(onClick = { if (enabled) menuExpanded = true }) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.actions),
            )
        }
    }
}

private data class ProfilePresentation(
    val label: String,
    val maskedAddress: String,
    val shareUri: String?,
)

@Composable
private fun rememberProfilePresentation(profile: AppConfig): ProfilePresentation {
    val initial = remember(profile) {
        ProfilePresentation(
            label = profile.label(),
            maskedAddress = if (profile.rawConfigJson.isBlank()) profile.maskedAddress() else "",
            shareUri = null,
        )
    }
    val presentation by produceState(initialValue = initial, profile) {
        value = withContext(Dispatchers.Default) {
            ProfilePresentation(
                label = profile.label(),
                maskedAddress = profile.maskedAddress(),
                shareUri = ProfileUriCodec.encode(profile),
            )
        }
    }
    return presentation
}

@Composable
private fun ProfileRow(
    modifier: Modifier = Modifier,
    profile: AppConfig,
    running: Boolean,
    health: ProfileHealth?,
    enabled: Boolean,
    onClick: () -> Unit,
    onShare: (String) -> Unit,
    onShowQrCode: () -> Unit,
    onEdit: () -> Unit,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val presentation = rememberProfilePresentation(profile)
    val shareable = presentation.shareUri != null
    val colors = MaterialTheme.colorScheme
    val degraded = running && health?.status == ProfileHealthStatus.Degraded
    val primaryContentColor = if (degraded) colors.onErrorContainer else colors.onSurface
    val secondaryContentColor = if (degraded) colors.onErrorContainer else colors.onSurfaceVariant
    val rowColor by animateColorAsState(
        targetValue = when {
            degraded -> colors.errorContainer
            running -> colors.secondaryContainer
            else -> colors.surfaceContainerLow
        },
        label = "profileRowColor",
    )
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val actionWidth = 80.dp
    val anchors = remember(density, layoutDirection) {
        DraggableAnchors {
            ProfileSwipeValue.Closed at 0f
            ProfileSwipeValue.Actions at swipeActionsOffset(
                widthPx = with(density) { (actionWidth * 2).toPx() },
                layoutDirection = layoutDirection,
            )
        }
    }
    val swipeState = remember(profile.id, anchors) {
        AnchoredDraggableState(ProfileSwipeValue.Closed, anchors)
    }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled && !dragging,
                ),
        ) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.surfaceContainerHighest),
                horizontalArrangement = Arrangement.End,
            ) {
                ProfileSwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.edit),
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    enabled = enabled,
                    onClick = onEdit,
                )
                ProfileSwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.delete),
                    containerColor = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                    enabled = enabled,
                    onClick = onDeleteRequest,
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(
                            x = swipeState.safeOffset().roundToInt(),
                            y = 0,
                        )
                    },
                shape = CardShape,
                color = rowColor,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .clickable(enabled = enabled) {
                            if (swipeState.settledValue == ProfileSwipeValue.Actions) {
                                scope.launch { swipeState.animateTo(ProfileSwipeValue.Closed) }
                            } else {
                                onClick()
                            }
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileStatusMark(running = running, degraded = degraded)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val healthLabel = if (running) profileHealthLabel(health) else null
                        val healthLabelStyle = MaterialTheme.typography.labelMedium.toSpanStyle().copy(
                            color = if (degraded) colors.onErrorContainer else colors.primary,
                        )
                        Text(
                            text = buildAnnotatedString {
                                append(profile.name)
                                healthLabel?.let {
                                    append(" · ")
                                    withStyle(healthLabelStyle) {
                                        append(it)
                                    }
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = primaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = presentation.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            presentation.maskedAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (degraded && !health?.error.isNullOrBlank()) {
                            Text(
                                profileHealthErrorSummary(health?.error.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryContentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    IconButton(
                        onClick = { presentation.shareUri?.let(onShare) },
                        enabled = shareable,
                    ) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.share),
                            tint = if (shareable) secondaryContentColor else primaryContentColor.copy(alpha = 0.38f),
                        )
                    }
                    IconButton(
                        onClick = onShowQrCode,
                        enabled = shareable,
                    ) {
                        Icon(
                            Icons.Rounded.QrCode2,
                            contentDescription = stringResource(R.string.show_qr_code),
                            tint = if (shareable) secondaryContentColor else primaryContentColor.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(swipeState.safeOffset().roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .draggable(
                        state = rememberDraggableState(onDelta = onDrag),
                        orientation = Orientation.Vertical,
                        enabled = enabled,
                        onDragStarted = { onDragStart() },
                        onDragStopped = { onDragEnd() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.reorder_profile),
                    tint = secondaryContentColor,
                )
            }
            Spacer(Modifier.width(96.dp))
        }
    }
}

private enum class ProfileSwipeValue {
    Closed,
    Actions,
}

private fun <T> AnchoredDraggableState<T>.safeOffset(): Float = offset.takeIf(Float::isFinite) ?: 0f

internal fun swipeActionsOffset(widthPx: Float, layoutDirection: LayoutDirection): Float {
    return if (layoutDirection == LayoutDirection.Ltr) -widthPx else widthPx
}

@Composable
private fun ProfileSwipeAction(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun ProfileStatusMark(running: Boolean, degraded: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = when {
            degraded -> colors.error
            running -> colors.primary
            else -> colors.surfaceContainerHighest
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    when {
                        degraded -> R.string.profile_degraded
                        running -> R.string.profile_connected
                        else -> R.string.profile_stopped
                    },
                ),
                tint = when {
                    degraded -> colors.onError
                    running -> colors.onPrimary
                    else -> colors.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun profileHealthLabel(health: ProfileHealth?): String? {
    return when (health?.status ?: ProfileHealthStatus.Unknown) {
        ProfileHealthStatus.Unknown -> "…"
        ProfileHealthStatus.Healthy -> health?.latencyMs?.let { latency ->
            stringResource(R.string.profile_health_latency, latency)
        }
        ProfileHealthStatus.Degraded -> {
            val failures = (health?.failures ?: 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.profile_health_degraded_failures, failures, failures)
        }
    }
}

private fun profileHealthErrorSummary(error: String): String {
    Regex("""close called for canceled stream \d+""").find(error)?.let { return it.value }
    Regex("""mux session dial is backing off for native QUIC \([^)]*\)""")
        .find(error)
        ?.let { return it.value }
    return error.substringAfterLast("; ").substringAfterLast(": ").trim().take(160)
}

@Composable
private fun ConnectionStatusMark(
    color: Color,
    containerColor: Color,
    icon: ImageVector = Icons.Rounded.Speed,
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProfileQrCodeDialog(
    profile: AppConfig,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bitmapResult by produceState<Result<Bitmap>?>(initialValue = null, profile) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerationMutex.withLock {
                currentCoroutineContext().ensureActive()
                val result = runRecoverableCatching {
                    val payload = ProfileUriCodec.encodeForQr(profile)
                        ?: throw IllegalArgumentException("profile cannot be encoded as a QR code")
                    // 768 px remains crisp on-screen while bounding peak bitmap/matrix memory.
                    generateQrCodeBitmap(payload, 768)
                }
                currentCoroutineContext().ensureActive()
                result
            }
        }
    }
    if (bitmapResult == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_qr_code)) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )
        return
    }
    val bitmap = bitmapResult?.getOrNull()
    if (bitmap == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_qr_code)) },
            text = { Text(stringResource(R.string.profile_qr_code_failed)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            },
        )
        return
    }

    val profileMeta = listOf(profile.label(), profile.maskedAddress())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DialogShape,
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = colors.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.profile_qr_code),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )

                if (profileMeta.isNotBlank()) {
                    Text(
                        text = profileMeta,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Flat pure-white stage (no elevation shadow under the code) for cleaner captures.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    shape = QrCardShape,
                    color = Color.White,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.profile_qr_code_description,
                            profile.name,
                        ),
                        contentScale = ContentScale.Fit,
                        // Nearest-neighbor keeps module edges crisp when Compose scales the bitmap.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            // Extra light margin beyond the encoded quiet zone helps camera framing.
                            .padding(24.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.profile_qr_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                )

                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(enabled: Boolean, onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.Hub,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            stringResource(R.string.empty_profiles),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.empty_profiles_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(onClick = onAdd, enabled = enabled) {
            Text(stringResource(R.string.add_profile))
        }
    }
}

@Composable
private fun BottomStatus(
    status: String,
    error: String,
    tcping: TcpingProgress,
    hasProfiles: Boolean,
    connectionsReady: Boolean,
    tcpingEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tcpingMessage = tcpingStatusText(tcping)
    val connectionsStarting = status == "Starting" || (status == "Running" && !connectionsReady)
    val text = when {
        error.isNotBlank() -> stringResource(R.string.error_prefix, error)
        tcpingMessage.isNotBlank() -> tcpingMessage
        status == "Running" && tcpingEnabled -> stringResource(R.string.connected_tap_test)
        connectionsStarting -> stringResource(R.string.connecting)
        status == "Stopping" -> stringResource(R.string.stopping)
        hasProfiles -> stringResource(R.string.not_connected_tap_profile)
        else -> stringResource(R.string.not_connected_add_profile)
    }
    val contentColor = when {
        error.isNotBlank() -> colors.error
        tcping.running -> colors.tertiary
        tcping.error.isNotBlank() && tcping.results.isEmpty() -> colors.error
        status == "Running" && tcpingEnabled -> colors.primary
        tcping.results.any { it.elapsedMs != null } && status == "Running" -> colors.primary
        connectionsStarting || status == "Stopping" -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    val statusIcon = when {
        error.isNotBlank() -> Icons.Rounded.Speed
        status == "Running" && connectionsReady -> Icons.Rounded.Check
        else -> Icons.Rounded.Speed
    }
    BottomAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 144.dp)
            .clickable(enabled = tcpingEnabled && !tcping.running, onClick = onClick),
        containerColor = colors.surfaceContainer,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionStatusMark(
                color = contentColor,
                containerColor = contentColor.copy(alpha = 0.12f),
                icon = statusIcon,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun tcpingStatusText(progress: TcpingProgress): String {
    if (progress.requestId == 0L) return ""
    if (progress.error.isNotBlank() && progress.results.isEmpty()) {
        return stringResource(R.string.tcping_failed_summary, progress.error)
    }
    val completed = progress.results.map { result ->
        result.elapsedMs?.let { elapsed ->
            stringResource(R.string.tcping_link_success, result.profileName, elapsed)
        } ?: stringResource(R.string.tcping_link_failed, result.profileName)
    }
    val parts = mutableListOf<String>()
    if (progress.running) {
        parts += stringResource(
            R.string.tcping_step,
            progress.targetLabel,
            progress.currentIndex,
            progress.total,
            progress.currentProfileName,
        )
    } else {
        parts += stringResource(R.string.tcping_complete, progress.targetLabel)
    }
    parts += completed
    progress.averageMs?.let { average ->
        parts += stringResource(
            R.string.tcping_average,
            average,
            progress.results.count { it.elapsedMs != null },
            progress.total,
        )
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val startFailedMessage = stringResource(R.string.start_failed)
    var settings by remember { mutableStateOf(RuntimeSettings()) }
    var socksPortText by remember { mutableStateOf(settings.socksPort.toString()) }
    var settingsDirty by remember { mutableStateOf(false) }
    var savingSettings by remember { mutableStateOf(false) }
    var settingsLoaded by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<AppConfig>>(emptyList()) }
    val settingsScope = rememberCoroutineScope()
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    val diagnostics = vpnState.diagnostics
    val mtuOptions = listOf("1280", "1360", "1400", "1500")
    val defaultPoolLabel = stringResource(R.string.route_outbound_proxy)
    val defaultDirectLabel = stringResource(R.string.route_outbound_direct)
    val defaultOutboundChoices = listOf(DefaultOutboundDynamicPool to defaultPoolLabel) +
        profiles.filter { it.rawConfigJson.isBlank() }.map { profile ->
            profile.id to "${profile.name} · ${profile.maskedAddress()} · ${profile.id.take(8)}"
        } +
        (DefaultOutboundDirect to defaultDirectLabel)
    val selectedDefaultOutboundLabel = defaultOutboundChoices
        .firstOrNull { it.first == settings.defaultOutbound }
        ?.second
        ?: defaultPoolLabel

    LaunchedEffect(context) {
        val (loadedSettings, loadedProfiles) = withContext(Dispatchers.IO) {
            readUiRuntimeSettings(context) to ProfileStore.load(context).profiles
        }
        settings = loadedSettings
        socksPortText = loadedSettings.socksPort.toString()
        profiles = loadedProfiles
        settingsLoaded = true
    }

    if (!settingsLoaded) {
        BackHandler(onBack = onBack)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    fun updateSettingsDraft(next: RuntimeSettings) {
        if (savingSettings) return
        if (next != settings) settingsDirty = true
        settings = next
    }

    fun leaveSettings() {
        val socksPort = socksPortText.toIntOrNull()
        if (savingSettings || socksPort == null || socksPort !in 1..65535) return
        if (!settingsDirty) {
            onBack()
            return
        }
        val next = settings.copy(socksPort = socksPort)
        savingSettings = true
        settingsScope.launch {
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) {
                    writeUiRuntimeSettings(context, next).map { readUiRuntimeSettings(context) }
                }
                saved.fold(
                    onSuccess = { persisted ->
                        settings = persisted
                        socksPortText = persisted.socksPort.toString()
                        applyRuntimeSettings(context)
                        settingsDirty = false
                        savingSettings = false
                        onBack()
                    },
                    onFailure = { error ->
                        savingSettings = false
                        reportUiError(error.message ?: startFailedMessage)
                    },
                )
            }
        }
    }

    BackHandler(onBack = ::leaveSettings)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(onBack = ::leaveSettings)
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                if (!settingsDirty) {
                    val (loadedSettings, loadedProfiles) = withContext(Dispatchers.IO) {
                        readUiRuntimeSettings(context) to ProfileStore.load(context).profiles
                    }
                    settings = loadedSettings
                    socksPortText = settings.socksPort.toString()
                    profiles = loadedProfiles
                }
                refreshRunningDiagnostics()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
        ) {
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.transparent_proxy_settings),
                        )
                        ChoiceRow("MTU", settings.mtu.toString(), mtuOptions, enabled = !savingSettings) { value ->
                            updateSettingsDraft(
                                settings.copy(mtu = value.toIntOrNull() ?: TcptunVpnService.DEFAULT_VPN_MTU),
                            )
                        }
                        ChoiceRow(
                            stringResource(R.string.local_proxy_protocol),
                            settings.localProxyProtocol,
                            LocalProxyProtocols,
                            enabled = !savingSettings,
                        ) { value ->
                            updateSettingsDraft(settings.copy(localProxyProtocol = value))
                        }
                        val socksPort = socksPortText.toIntOrNull()
                        OutlinedTextField(
                            value = socksPortText,
                            onValueChange = { value ->
                                if (savingSettings) return@OutlinedTextField
                                val digits = value.filter { it.isDigit() }.take(5)
                                socksPortText = digits
                                val port = digits.toIntOrNull()
                                if (port != null && port in 1..65535) {
                                    updateSettingsDraft(settings.copy(socksPort = port))
                                }
                            },
                            label = { FieldChromeText(stringResource(R.string.socks_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !savingSettings,
                            isError = socksPort == null || socksPort !in 1..65535,
                            supportingText = {
                                if (socksPort == null || socksPort !in 1..65535) {
                                    FieldChromeText(stringResource(R.string.socks_port_error))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            stringResource(R.string.socks_listen_all),
                            settings.socksListenAll,
                            enabled = !savingSettings,
                        ) { checked ->
                            updateSettingsDraft(settings.copy(socksListenAll = checked))
                        }
                        ToggleRow(
                            stringResource(R.string.route_local_proxy_traffic),
                            settings.routeLocalProxyTraffic,
                            enabled = !savingSettings,
                        ) { checked ->
                            updateSettingsDraft(settings.copy(routeLocalProxyTraffic = checked))
                        }
                        Text(
                            stringResource(R.string.route_local_proxy_traffic_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChoiceRow(
                            stringResource(R.string.default_outbound),
                            selectedDefaultOutboundLabel,
                            defaultOutboundChoices.map { it.second },
                            enabled = !savingSettings,
                        ) { selected ->
                            val choice = defaultOutboundChoices.firstOrNull { it.second == selected }
                                ?: return@ChoiceRow
                            updateSettingsDraft(settings.copy(defaultOutbound = choice.first))
                        }
                        Text(
                            stringResource(R.string.default_outbound_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = settings.socksUsername,
                            onValueChange = { value ->
                                updateSettingsDraft(settings.copy(socksUsername = truncateSocksCredential(value)))
                            },
                            label = { FieldChromeText(stringResource(R.string.socks_username)) },
                            singleLine = true,
                            enabled = !savingSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = settings.socksPassword,
                            onValueChange = { value ->
                                updateSettingsDraft(settings.copy(socksPassword = truncateSocksCredential(value)))
                            },
                            label = { FieldChromeText(stringResource(R.string.socks_password)) },
                            singleLine = true,
                            enabled = !savingSettings,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.native_tun_capabilities_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.socks_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.runtime_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.logs),
                        )
                        ChoiceRow(
                            stringResource(R.string.log_level),
                            settings.logLevel,
                            LogLevels,
                            enabled = !savingSettings,
                        ) { value ->
                            updateSettingsDraft(settings.copy(logLevel = value))
                        }
                        Text(
                            stringResource(R.string.log_level_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Check,
                            title = stringResource(R.string.current_effective),
                        )
                        DiagnosticsLine("MTU", diagnostics.mtu.toString())
                        DiagnosticsLine(stringResource(R.string.log_level), settings.logLevel)
                        DiagnosticsLine(stringResource(R.string.local_proxy_protocol), settings.localProxyProtocol)
                        DiagnosticsLine(stringResource(R.string.socks_listen), TcptunVpnService.localSocksListenAddr(settings))
                        DiagnosticsLine(stringResource(R.string.socks_auth), if (settings.socksUsername.isNotEmpty() || settings.socksPassword.isNotEmpty()) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(
                            stringResource(R.string.route_local_proxy_traffic),
                            if (settings.routeLocalProxyTraffic) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                        )
                        DiagnosticsLine(
                            stringResource(R.string.default_outbound),
                            selectedDefaultOutboundLabel,
                        )
                        DiagnosticsLine(stringResource(R.string.vpn_traffic_mode), stringResource(R.string.tcp_udp))
                    }
                }
            }
        }
        }
    }
}

private suspend fun mutateManagedRouteRules(
    context: Context,
    transform: (List<ManagedRouteRule>) -> List<ManagedRouteRule>,
): Pair<List<ManagedRouteRule>, ProfilesState> = ProcessProfileMutationMutex.withLock {
    ProcessRouteRuleMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            // Always transform the latest committed snapshot. This also makes
            // flow-analysis conversion safe while route management is open in
            // another Activity instance.
            val currentRules = RouteRuleStore.loadAuthoritative(context).getOrThrow()
            val next = transform(currentRules)
            val profileSnapshot = ProfileStore.snapshot(context)
            val authoritativeProfiles = profileSnapshot.requireAuthoritativeState()
            val possiblePlans = buildList {
                if (authoritativeProfiles.activeIds.isNotEmpty()) {
                    add(authoritativeProfiles.runPlan())
                }
                authoritativeProfiles.profiles
                    .firstOrNull { it.rawConfigJson.isBlank() }
                    ?.let { profile ->
                        // A structured run plan contains every structured
                        // profile so future hot membership updates remain possible.
                        add(authoritativeProfiles.copy(activeIds = setOf(profile.id)).runPlan())
                    }
                authoritativeProfiles.profiles
                    .filter { it.rawConfigJson.isNotBlank() }
                    .forEach { profile ->
                        add(authoritativeProfiles.copy(activeIds = setOf(profile.id)).runPlan())
                    }
            }.distinct()
            val currentRouteSize = estimatedEnabledRouteRuntimePayloadLength(currentRules)
            val nextRouteSize = estimatedEnabledRouteRuntimePayloadLength(next)
            possiblePlans.forEach { possiblePlan ->
                val candidateFailure = runCatching {
                    TcptunVpnService.preflightStartPayload(
                        context = context,
                        sourcePlan = possiblePlan,
                        managedRouteRules = next,
                    )
                }.exceptionOrNull() ?: return@forEach
                val currentConfigurationFits = runCatching {
                    TcptunVpnService.preflightStartPayload(
                        context = context,
                        sourcePlan = possiblePlan,
                        managedRouteRules = currentRules,
                    )
                }.isSuccess
                // Do not let an unrelated already-oversized imported profile
                // prevent the user from deleting or shrinking route data.
                if (currentConfigurationFits || nextRouteSize > currentRouteSize) {
                    throw candidateFailure
                }
            }
            RouteRuleStore.save(context, next).getOrThrow()
            RouteRuleStore.loadAuthoritative(context).getOrThrow() to authoritativeProfiles
        }
    }
}

@Composable
private fun FlowAnalysisPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val startFailedMessage = stringResource(R.string.start_failed)
    val runtimeState by TcptunState.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(RuntimeSettings()) }
    var installedApps by remember { mutableStateOf<List<InstalledRouteApp>>(emptyList()) }
    var selectionSaving by remember { mutableStateOf(false) }
    var routeRuleSaving by remember { mutableStateOf(false) }
    var showRouteRuleDialog by remember { mutableStateOf(false) }
    var routeRuleOutbound by remember { mutableStateOf(ManagedRouteOutbound.Proxy) }
    var routeRuleResult by remember { mutableStateOf("") }
    var routeRuleError by remember { mutableStateOf("") }
    var flowLeaveRequested by remember { mutableStateOf(false) }
    var pageLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flowSettingsMutex = remember { Mutex() }
    val flowAnalysisDisabled = stringResource(R.string.flow_analysis_disabled)
    val flowAppOptions = listOf(flowAnalysisDisabled) + installedApps.map(InstalledRouteApp::displayName)
    val selectedPackage = settings.flowAnalysisApp
    val selectedAppLabel = installedApps.firstOrNull { it.packageName == selectedPackage }?.displayName
        ?: selectedPackage.ifBlank { flowAnalysisDisabled }
    val events = runtimeState.flowEvents.asReversed()
    val routeRuleSuggestions = remember(events, routeRuleOutbound) {
        buildFlowRouteRuleSuggestions(events, routeRuleOutbound)
    }

    fun selectFlowApp(selected: String) {
        if (selectionSaving || flowLeaveRequested) return
        val packageName = installedApps
            .firstOrNull { it.displayName == selected }
            ?.packageName
            .orEmpty()
        if (packageName == selectedPackage) return
        selectionSaving = true
        scope.launch {
            withContext(NonCancellable) {
                flowSettingsMutex.withLock {
                    val saved = withContext(Dispatchers.IO) {
                        writeUiRuntimeSettings(context, settings.copy(flowAnalysisApp = packageName))
                            .map { readUiRuntimeSettings(context) }
                    }
                    saved.fold(
                        onSuccess = { persisted ->
                            settings = persisted
                            selectionSaving = false
                            applyFlowAnalysisSettings(context)
                            if (flowLeaveRequested && !routeRuleSaving) {
                                flowLeaveRequested = false
                                onBack()
                            }
                        },
                        onFailure = { error ->
                            selectionSaving = false
                            flowLeaveRequested = false
                            reportUiError(error.message ?: startFailedMessage)
                        },
                    )
                }
            }
        }
    }

    fun leaveFlowAnalysis() {
        if (selectionSaving || routeRuleSaving) {
            flowLeaveRequested = true
        } else {
            onBack()
        }
    }

    fun createRouteRules() {
        val suggestions = routeRuleSuggestions
        if (routeRuleSaving || suggestions.isEmpty()) return
        routeRuleSaving = true
        routeRuleError = ""
        scope.launch {
            try {
                withContext(NonCancellable) {
                    mutateManagedRouteRules(context) { existing ->
                        mergeFlowRouteRuleSuggestions(existing, suggestions)
                    }
                    applyRuntimeSettings(context, forceRestart = true)
                    routeRuleResult = resources.getString(
                        R.string.flow_analysis_rules_created,
                        suggestions.size,
                    )
                    showRouteRuleDialog = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                routeRuleError = failure.message ?: startFailedMessage
            } finally {
                routeRuleSaving = false
                if (flowLeaveRequested && !selectionSaving) {
                    flowLeaveRequested = false
                    onBack()
                }
            }
        }
    }

    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            readUiRuntimeSettings(context) to loadInstalledRouteApps(context)
        }
        settings = loaded.first
        installedApps = loaded.second
        pageLoaded = true
    }
    LaunchedEffect(selectedPackage, pageLoaded) {
        if (pageLoaded) TcptunState.setFlowAnalysisApp(selectedPackage)
    }
    BackHandler(onBack = ::leaveFlowAnalysis)

    if (!pageLoaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.flow_analysis),
                onBack = ::leaveFlowAnalysis,
                actions = {
                    TextButton(onClick = TcptunState::clearFlowEvents, enabled = events.isNotEmpty()) {
                        Text(stringResource(R.string.clear))
                    }
                },
            )
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                val refreshed = flowSettingsMutex.withLock {
                    withContext(Dispatchers.IO) {
                        readUiRuntimeSettings(context) to loadInstalledRouteApps(context)
                    }
                }
                settings = refreshed.first
                installedApps = refreshed.second
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
        ) {
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle(Icons.Rounded.Hub, stringResource(R.string.flow_analysis_current))
                        ChoiceRow(
                            title = stringResource(R.string.flow_analysis_app),
                            value = selectedAppLabel,
                            options = flowAppOptions,
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !selectionSaving,
                            onChange = ::selectFlowApp,
                        )
                        Text(
                            stringResource(R.string.flow_analysis_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DiagnosticsLine(stringResource(R.string.flow_analysis_events), events.size.toString())
                        FilledTonalButton(
                            onClick = { showRouteRuleDialog = true },
                            enabled = routeRuleSuggestions.isNotEmpty() && !routeRuleSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.flow_analysis_create_rules))
                        }
                        if (routeRuleResult.isNotBlank()) {
                            Text(
                                routeRuleResult,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (routeRuleError.isNotBlank()) {
                            Text(
                                routeRuleError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (runtimeState.flowDroppedEvents > 0) {
                            Text(
                                stringResource(R.string.flow_analysis_dropped, runtimeState.flowDroppedEvents),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> item {
                    FlowAnalysisEmptyState(stringResource(R.string.flow_analysis_android_10_required))
                }
                selectedPackage.isBlank() -> item {
                    FlowAnalysisEmptyState(stringResource(R.string.flow_analysis_select_hint))
                }
                events.isEmpty() -> item {
                    FlowAnalysisEmptyState(stringResource(R.string.flow_analysis_empty))
                }
                else -> itemsIndexed(
                    items = events,
                    key = { index, event -> "${event.sessionId}:${event.sequence}:$index" },
                ) { _, event ->
                    FlowAnalysisEventCard(event)
                }
            }
        }
        }
    }

    if (showRouteRuleDialog) {
        FlowRouteRuleDialog(
            suggestions = routeRuleSuggestions,
            outbound = routeRuleOutbound,
            saving = routeRuleSaving,
            error = routeRuleError,
            onOutboundChange = {
                routeRuleOutbound = it
                routeRuleError = ""
            },
            onDismiss = {
                if (!routeRuleSaving) {
                    showRouteRuleDialog = false
                    routeRuleError = ""
                }
            },
            onConfirm = ::createRouteRules,
        )
    }
}

@Composable
private fun FlowAnalysisEmptyState(message: String) {
    SettingsCard {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FlowAnalysisEventCard(event: FlowAnalysisEvent) {
    val timeLabel = remember(event.timestampMs) {
        event.timestampMs.takeIf { it > 0 }
            ?.let { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) }
            .orEmpty()
    }
    SettingsCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    event.displayDestination,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    R.string.flow_analysis_event_summary,
                    event.network.uppercase(),
                    event.type,
                    event.port,
                    event.outboundTag.ifBlank { stringResource(R.string.none) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (event.domain.isNotBlank() && event.originalIp.isNotBlank()) {
                Text(
                    stringResource(R.string.flow_analysis_original_ip, event.originalIp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.routeReason.isNotBlank()) {
                Text(
                    stringResource(R.string.flow_analysis_route, event.routeReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FlowRouteRuleDialog(
    suggestions: List<ManagedRouteRule>,
    outbound: ManagedRouteOutbound,
    saving: Boolean,
    error: String,
    onOutboundChange: (ManagedRouteOutbound) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val proxyLabel = stringResource(R.string.route_outbound_proxy)
    val directLabel = stringResource(R.string.route_outbound_direct)
    val choices = listOf(proxyLabel, directLabel)
    val selected = if (outbound == ManagedRouteOutbound.Proxy) proxyLabel else directLabel
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null) },
        title = { Text(stringResource(R.string.flow_analysis_create_rules)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.flow_analysis_rules_note, suggestions.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    title = stringResource(R.string.route_rule_outbound),
                    value = selected,
                    options = choices,
                    enabled = !saving,
                ) { choice ->
                    onOutboundChange(
                        if (choice == directLabel) ManagedRouteOutbound.Direct else ManagedRouteOutbound.Proxy,
                    )
                }
                Text(
                    stringResource(R.string.flow_analysis_rules_preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                suggestions.take(8).forEach { rule ->
                    Text(
                        "${routeRuleTypeLabel(rule.type)} · ${rule.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (suggestions.size > 8) {
                    Text(
                        stringResource(R.string.flow_analysis_rules_more, suggestions.size - 8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = suggestions.isNotEmpty() && !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.flow_analysis_confirm_rules, suggestions.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var profileState by remember { mutableStateOf(ProfilesState(emptyList())) }
    val routeProfiles = profileState.profiles.filter { it.rawConfigJson.isBlank() }
    var installedApps by remember { mutableStateOf<List<InstalledRouteApp>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ManagedRouteRule>>(emptyList()) }
    var routeDataLoaded by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ManagedRouteRule?>(null) }
    var deleteCandidate by remember { mutableStateOf<ManagedRouteRule?>(null) }
    var routeActionsExpanded by remember { mutableStateOf(false) }
    var smartMergePreview by remember { mutableStateOf<SmartRouteMergeResult?>(null) }
    var notice by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var routeSaveCount by remember { mutableIntStateOf(0) }
    var routeLeaveRequested by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var draggedRuleId by remember { mutableStateOf<String?>(null) }
    var draggedRuleOffset by remember { mutableStateOf(0f) }
    var rulesBeforeDrag by remember { mutableStateOf<List<ManagedRouteRule>?>(null) }
    var reorderScrollJob by remember { mutableStateOf<Job?>(null) }
    val reorderScope = rememberCoroutineScope()
    val routeMutationMutex = ProcessRouteRuleMutationMutex
    val reorderScrollStep = with(LocalDensity.current) { 24.dp.toPx() }
    val routeSaving = routeSaveCount > 0
    val routeInteractionEnabled = !routeSaving && !routeLeaveRequested
    val smartMergeResult = remember(rules) { smartMergeManagedRouteRules(rules) }

    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                ProfileStore.load(context),
                loadInstalledRouteApps(context),
                RouteRuleStore.load(context),
            )
        }
        profileState = loaded.first
        installedApps = loaded.second
        rules = loaded.third
        routeDataLoaded = true
    }

    if (!routeDataLoaded) {
        BackHandler(onBack = onBack)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    suspend fun persist(
        transform: (List<ManagedRouteRule>) -> List<ManagedRouteRule>,
    ): Boolean {
        routeSaveCount += 1
        return try {
            withContext(NonCancellable) {
                val (persisted, currentProfiles) = mutateManagedRouteRules(context, transform)
                rules = persisted
                profileState = currentProfiles
                dirty = true
                error = ""
                // Keep the running service synchronized even if the Activity is recreated
                // before this page can execute its normal leave path.
                applyRuntimeSettings(context, forceRestart = true)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message.orEmpty()
            false
        } finally {
            routeSaveCount = (routeSaveCount - 1).coerceAtLeast(0)
            if (routeLeaveRequested && routeSaveCount == 0) {
                routeLeaveRequested = false
                if (dirty) applyRuntimeSettings(context, forceRestart = true)
                onBack()
            }
        }
    }

    fun leave() {
        if (routeSaving) {
            routeLeaveRequested = true
            return
        }
        if (dirty) applyRuntimeSettings(context, forceRestart = true)
        onBack()
    }

    fun startRuleDrag(ruleId: String) {
        if (!routeInteractionEnabled) return
        routeActionsExpanded = false
        draggedRuleId = ruleId
        draggedRuleOffset = 0f
        rulesBeforeDrag = rules
    }

    fun dragRule(ruleId: String, deltaY: Float) {
        if (!routeInteractionEnabled || draggedRuleId != ruleId || !deltaY.isFinite()) return
        draggedRuleOffset += deltaY
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.firstOrNull { it.key == ruleId } ?: return
        val draggedCenter = draggedItem.offset + draggedRuleOffset + draggedItem.size / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            item.key != ruleId &&
                rules.any { it.id == item.key } &&
                draggedCenter >= item.offset &&
                draggedCenter <= item.offset + item.size
        }
        if (targetItem != null) {
            val fromIndex = rules.indexOfFirst { it.id == ruleId }
            val toIndex = rules.indexOfFirst { it.id == targetItem.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                rules = rules.toMutableList().also { reordered ->
                    reordered.add(toIndex, reordered.removeAt(fromIndex))
                }
                draggedRuleOffset += draggedItem.offset - targetItem.offset
            }
        }

        if (reorderScrollJob?.isActive != true) {
            reorderScrollJob = reorderScope.launch {
                while (draggedRuleId == ruleId) {
                    val layoutInfo = listState.layoutInfo
                    val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == ruleId } ?: break
                    val currentTop = currentItem.offset + draggedRuleOffset
                    val currentBottom = currentTop + currentItem.size
                    val scrollDelta = when {
                        currentTop < layoutInfo.viewportStartOffset -> -reorderScrollStep
                        currentBottom > layoutInfo.viewportEndOffset -> reorderScrollStep
                        else -> break
                    }
                    val consumed = listState.scrollBy(scrollDelta)
                    if (consumed == 0f) break
                    if (draggedRuleId == ruleId) draggedRuleOffset += consumed
                    delay(16)
                }
            }
        }
    }

    fun finishRuleDrag(commit: Boolean) {
        val original = rulesBeforeDrag
        val reordered = rules
        reorderScrollJob?.cancel()
        reorderScrollJob = null
        draggedRuleId = null
        draggedRuleOffset = 0f
        rulesBeforeDrag = null
        if (!commit) {
            if (original != null) rules = original
        } else if (original != null && original.map { it.id } != reordered.map { it.id }) {
            val reorderedIds = reordered.map(ManagedRouteRule::id)
            reorderScope.launch {
                val saved = persist { current ->
                    val byId = current.associateBy(ManagedRouteRule::id)
                    buildList {
                        reorderedIds.mapNotNullTo(this) { byId[it] }
                        current.filterTo(this) { it.id !in reorderedIds }
                    }
                }
                if (!saved) rules = original
            }
        }
    }

    BackHandler {
        if (routeActionsExpanded) routeActionsExpanded = false else leave()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.route_management),
                onBack = ::leave,
            )
        },
        floatingActionButton = {
            RouteActionFabMenu(
                expanded = routeActionsExpanded,
                enabled = routeInteractionEnabled,
                onToggle = {
                    if (routeInteractionEnabled || routeActionsExpanded) {
                        routeActionsExpanded = !routeActionsExpanded
                    }
                },
                onAdd = {
                    routeActionsExpanded = false
                    editingRule = ManagedRouteRule()
                },
                onSmartMerge = {
                    routeActionsExpanded = false
                    notice = ""
                    error = ""
                    if (smartMergeResult.changed) {
                        smartMergePreview = smartMergeResult
                    } else {
                        notice = resources.getString(R.string.smart_merge_no_changes)
                    }
                },
            )
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                val refreshed = routeMutationMutex.withLock {
                    val shouldReloadRules = draggedRuleId == null && !dirty && routeSaveCount == 0
                    withContext(Dispatchers.IO) {
                        Triple(
                            ProfileStore.load(context),
                            loadInstalledRouteApps(context),
                            if (shouldReloadRules) RouteRuleStore.load(context) else null,
                        )
                    }
                }
                profileState = refreshed.first
                installedApps = refreshed.second
                refreshed.third?.let { rules = it }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
        ) {
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.AutoMirrored.Rounded.AltRoute,
                            title = stringResource(R.string.route_rules_count, rules.count { it.enabled }),
                        )
                        Text(
                            stringResource(R.string.route_management_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (error.isNotBlank()) {
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (notice.isNotBlank()) {
                            Text(
                                notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    RouteRulesEmptyState()
                }
            }
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                val dragging = draggedRuleId == rule.id
                ManagedRouteRuleRow(
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) draggedRuleOffset else 0f
                            shadowElevation = if (dragging) 8.dp.toPx() else 0f
                            shape = CardShape
                        },
                    rule = rule,
                    profiles = routeProfiles,
                    enabled = routeInteractionEnabled,
                    onEdit = { if (routeInteractionEnabled) editingRule = rule },
                    onEnabledChange = { enabled ->
                        if (!routeInteractionEnabled) return@ManagedRouteRuleRow
                        reorderScope.launch {
                            persist { current ->
                                val currentIndex = current.indexOfFirst { it.id == rule.id }
                                if (currentIndex < 0) current else current.toMutableList().also {
                                    it[currentIndex] = it[currentIndex].copy(enabled = enabled)
                                }
                            }
                        }
                    },
                    dragging = dragging,
                    onDragStart = { startRuleDrag(rule.id) },
                    onDrag = { deltaY -> dragRule(rule.id, deltaY) },
                    onDragEnd = { finishRuleDrag(commit = true) },
                    onDeleteRequest = {
                        if (routeInteractionEnabled) deleteCandidate = rule
                    },
                )
            }
        }
        }
    }

    editingRule?.let { rule ->
        ManagedRouteRuleDialog(
            rule = rule,
            profiles = routeProfiles,
            installedApps = installedApps,
            isNew = rules.none { it.id == rule.id },
            onDismiss = { if (routeInteractionEnabled) editingRule = null },
            onSave = { updated ->
                if (!routeInteractionEnabled) return@ManagedRouteRuleDialog
                reorderScope.launch {
                    val saved = persist { current ->
                        val index = current.indexOfFirst { it.id == updated.id }
                        current.toMutableList().also { next ->
                            if (index >= 0) next[index] = updated else next.add(updated)
                        }
                    }
                    if (saved) editingRule = null
                }
            },
        )
    }

    smartMergePreview?.let { preview ->
        SmartRouteMergeDialog(
            result = preview,
            saving = routeSaving,
            error = error,
            onDismiss = {
                if (!routeSaving) {
                    smartMergePreview = null
                    error = ""
                }
            },
            onConfirm = {
                if (!routeInteractionEnabled) return@SmartRouteMergeDialog
                reorderScope.launch {
                    val saved = persist { current -> smartMergeManagedRouteRules(current).rules }
                    if (saved) {
                        smartMergePreview = null
                        notice = resources.getString(
                            R.string.smart_merge_complete,
                            preview.removedRuleCount,
                        )
                    }
                }
            },
        )
    }

    deleteCandidate?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_route_rule)) },
            text = { Text(stringResource(R.string.delete_route_rule_message, rule.value)) },
            confirmButton = {
                TextButton(
                    enabled = routeInteractionEnabled,
                    onClick = {
                        reorderScope.launch {
                            if (persist { current -> current.filterNot { it.id == rule.id } }) {
                                deleteCandidate = null
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = routeInteractionEnabled,
                    onClick = { deleteCandidate = null },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RouteActionFabMenu(
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onSmartMerge: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (expanded) {
            RouteActionFabItem(
                label = stringResource(R.string.smart_merge_route_rules),
                icon = Icons.Rounded.Hub,
                enabled = enabled,
                onClick = onSmartMerge,
            )
            RouteActionFabItem(
                label = stringResource(R.string.add_route_rule),
                icon = Icons.Rounded.Add,
                enabled = enabled,
                onClick = onAdd,
            )
        }
        FloatingActionButton(onClick = onToggle) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(
                    if (expanded) R.string.close_route_actions else R.string.route_actions,
                ),
            )
        }
    }
}

@Composable
private fun RouteActionFabItem(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CardShapeCompact,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        SmallFloatingActionButton(
            onClick = { if (enabled) onClick() },
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun SmartRouteMergeDialog(
    result: SmartRouteMergeResult,
    saving: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Hub, contentDescription = null) },
        title = { Text(stringResource(R.string.smart_merge_route_rules)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.smart_merge_summary,
                        result.rules.size + result.removedRuleCount,
                        result.rules.size,
                        result.removedRuleCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.smart_merge_preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                result.groups.take(6).forEach { group ->
                    val sourcePreview = group.sourceRules
                        .take(2)
                        .joinToString(" · ", transform = ManagedRouteRule::value)
                    val suffix = if (group.sourceRules.size > 2) " · …" else ""
                    Text(
                        "$sourcePreview$suffix  →  ${routeRuleTypeLabel(group.mergedRule.type)} · " +
                            group.mergedRule.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (result.groups.size > 6) {
                    Text(
                        stringResource(R.string.flow_analysis_rules_more, result.groups.size - 6),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.smart_merge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ManagedRouteRuleRow(
    modifier: Modifier = Modifier,
    rule: ManagedRouteRule,
    profiles: List<AppConfig>,
    enabled: Boolean,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val actionWidth = 80.dp
    val anchors = remember(density, layoutDirection) {
        DraggableAnchors {
            ProfileSwipeValue.Closed at 0f
            ProfileSwipeValue.Actions at swipeActionsOffset(
                widthPx = with(density) { (actionWidth * 2).toPx() },
                layoutDirection = layoutDirection,
            )
        }
    }
    val swipeState = remember(rule.id, anchors) {
        AnchoredDraggableState(ProfileSwipeValue.Closed, anchors)
    }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled && !dragging,
                ),
        ) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.surfaceContainerHighest),
                horizontalArrangement = Arrangement.End,
            ) {
                ProfileSwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.edit),
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    enabled = enabled,
                    onClick = onEdit,
                )
                ProfileSwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.delete),
                    containerColor = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                    enabled = enabled,
                    onClick = onDeleteRequest,
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        IntOffset(
                            x = swipeState.safeOffset().roundToInt(),
                            y = 0,
                        )
                    },
                shape = CardShape,
                color = if (rule.enabled) colors.surfaceContainerLow else colors.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .clickable(
                            enabled = enabled && swipeState.settledValue == ProfileSwipeValue.Actions,
                        ) {
                            // Tap only closes an open swipe; edit is via the swipe action.
                            scope.launch { swipeState.animateTo(ProfileSwipeValue.Closed) }
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            routeRuleTypeLabel(rule.type),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                        Text(rule.value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                        Text(
                            routeOutboundLabel(rule, profiles),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(48.dp))
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = enabled,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(swipeState.safeOffset().roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .draggable(
                        state = rememberDraggableState(onDelta = onDrag),
                        orientation = Orientation.Vertical,
                        enabled = enabled,
                        onDragStarted = { onDragStart() },
                        onDragStopped = { onDragEnd() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.reorder_rule),
                    tint = colors.onSurfaceVariant,
                )
            }
            // Keep the drag handle left of the in-row Switch (approx. switch width + padding).
            Spacer(Modifier.width(58.dp))
        }
    }
}

@Composable
private fun RouteRulesEmptyState() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.AltRoute,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Text(
            stringResource(R.string.empty_route_rules),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            stringResource(R.string.empty_route_rules_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ManagedRouteRuleDialog(
    rule: ManagedRouteRule,
    profiles: List<AppConfig>,
    installedApps: List<InstalledRouteApp>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ManagedRouteRule) -> Unit,
) {
    var type by remember(rule.id) { mutableStateOf(rule.type) }
    var value by remember(rule.id) {
        mutableStateOf(rule.value.take(MaxManagedRouteRuleValueLength))
    }
    var outbound by remember(rule.id) { mutableStateOf(rule.outbound) }
    var outboundProfileId by remember(rule.id) { mutableStateOf(rule.outboundProfileId) }
    var enabled by remember(rule.id) { mutableStateOf(rule.enabled) }
    var invalid by remember(rule.id) { mutableStateOf(false) }
    val types = ManagedRouteRuleType.entries.filter {
        it != ManagedRouteRuleType.App || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || rule.type == it
    }
    val typeLabels = types.map { routeRuleTypeLabel(it) }
    val directLabel = stringResource(R.string.route_outbound_direct)
    val poolLabel = stringResource(R.string.route_outbound_proxy)
    val proxyChoices = listOf("" to poolLabel) + profiles.map { profile ->
        profile.id to "${profile.name} · ${profile.maskedAddress()} · ${profile.id.take(8)}"
    }
    val outboundChoices = proxyChoices + ("__direct__" to directLabel)
    val selectedOutboundLabel = if (outbound == ManagedRouteOutbound.Direct) {
        directLabel
    } else {
        outboundChoices.firstOrNull { it.first == outboundProfileId }?.second ?: poolLabel
    }
    val appChoices = installedApps.map(InstalledRouteApp::displayName)
    val selectedAppLabel = installedApps.firstOrNull { it.packageName == value }?.displayName
        ?: value.ifBlank { stringResource(R.string.route_select_app) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_route_rule else R.string.edit_route_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceRow(stringResource(R.string.route_rule_type), routeRuleTypeLabel(type), typeLabels) { selected ->
                    typeLabels.indexOf(selected)
                        .takeIf { it in types.indices }
                        ?.let { selectedIndex -> type = types[selectedIndex] }
                    invalid = false
                }
                if (type == ManagedRouteRuleType.App && appChoices.isNotEmpty()) {
                    ChoiceRow(
                        stringResource(R.string.route_select_app),
                        selectedAppLabel,
                        appChoices,
                    ) { selected ->
                        value = installedApps.firstOrNull { it.displayName == selected }?.packageName.orEmpty()
                        invalid = false
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.take(MaxManagedRouteRuleValueLength)
                        invalid = false
                    },
                    label = {
                        FieldChromeText(
                            stringResource(
                                if (type == ManagedRouteRuleType.App) R.string.route_app_package
                                else R.string.route_rule_value,
                            ),
                        )
                    },
                    supportingText = {
                        FieldChromeText(
                            if (invalid) stringResource(R.string.invalid_route_rule)
                            else routeRuleExample(type),
                        )
                    },
                    isError = invalid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow(
                    stringResource(R.string.route_rule_outbound),
                    selectedOutboundLabel,
                    outboundChoices.map { it.second },
                ) { selected ->
                    val choice = outboundChoices.firstOrNull { it.second == selected } ?: return@ChoiceRow
                    if (choice.first == "__direct__") {
                        outbound = ManagedRouteOutbound.Direct
                        outboundProfileId = ""
                    } else {
                        outbound = ManagedRouteOutbound.Proxy
                        outboundProfileId = choice.first
                    }
                }
                ToggleRow(stringResource(R.string.enabled), enabled) { enabled = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = rule.copy(
                        type = type,
                        value = value,
                        outbound = outbound,
                        outboundProfileId = outboundProfileId,
                        enabled = enabled,
                    ).normalized()
                    if (updated.isValid()) onSave(updated) else invalid = true
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun routeRuleTypeLabel(type: ManagedRouteRuleType): String = when (type) {
    ManagedRouteRuleType.Domain -> stringResource(R.string.route_type_domain)
    ManagedRouteRuleType.DomainSuffix -> stringResource(R.string.route_type_domain_suffix)
    ManagedRouteRuleType.DomainRegex -> stringResource(R.string.route_type_domain_regex)
    ManagedRouteRuleType.IP -> stringResource(R.string.route_type_ip)
    ManagedRouteRuleType.IPCidr -> stringResource(R.string.route_type_ip_cidr)
    ManagedRouteRuleType.IPRange -> stringResource(R.string.route_type_ip_range)
    ManagedRouteRuleType.App -> stringResource(R.string.route_type_app)
}

@Composable
private fun routeOutboundLabel(
    rule: ManagedRouteRule,
    profiles: List<AppConfig>,
): String {
    if (rule.outbound == ManagedRouteOutbound.Direct) return stringResource(R.string.route_outbound_direct)
    if (rule.outboundProfileId.isBlank()) return stringResource(R.string.route_outbound_proxy)
    return profiles.firstOrNull { it.id == rule.outboundProfileId }?.let { "${it.name} · ${it.maskedAddress()}" }
        ?: stringResource(R.string.route_outbound_proxy)
}

@Composable
private fun routeRuleExample(type: ManagedRouteRuleType): String = stringResource(
    when (type) {
        ManagedRouteRuleType.Domain -> R.string.route_example_domain
        ManagedRouteRuleType.DomainSuffix -> R.string.route_example_domain_suffix
        ManagedRouteRuleType.DomainRegex -> R.string.route_example_domain_regex
        ManagedRouteRuleType.IP -> R.string.route_example_ip
        ManagedRouteRuleType.IPCidr -> R.string.route_example_ip_cidr
        ManagedRouteRuleType.IPRange -> R.string.route_example_ip_range
        ManagedRouteRuleType.App -> R.string.route_example_app
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.settings),
        onBack = onBack,
    )
}

@Composable
private fun EditProfilePage(initial: AppConfig, onBack: () -> Unit, onSave: (AppConfig) -> Unit) {
    var config by remember(initial.id) { mutableStateOf(initial.boundedForEditor()) }
    var useFullConfig by remember(initial.id) { mutableStateOf(initial.rawConfigJson.isNotBlank()) }
    var formError by remember(initial.id) { mutableStateOf("") }
    var validating by remember(initial.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val invalidProfileMessage = stringResource(R.string.invalid_profile_link)

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditTopBar(
                title = if (initial.serverHost.isBlank() && initial.rawConfigJson.isBlank()) {
                    stringResource(R.string.add_profile)
                } else {
                    stringResource(R.string.edit_profile)
                },
                onBack = onBack,
                saveEnabled = !validating,
                onSave = {
                    if (validating) return@EditTopBar
                    validating = true
                    scope.launch {
                        try {
                            var candidate = config
                            while (true) {
                                withContext(Dispatchers.Default) { validateImportedProfile(candidate) }
                                val latest = config
                                if (latest == candidate) {
                                    onSave(candidate)
                                    break
                                }
                                candidate = latest
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val message = failure.message?.takeIf(String::isNotBlank) ?: invalidProfileMessage
                            formError = message
                            reportUiError(message)
                        } finally {
                            validating = false
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (formError.isNotBlank()) {
                    Text(
                        formError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                        OutlinedTextField(
                            value = config.name,
                            onValueChange = {
                                config = config.copy(name = it.take(MaxProfileNameInputLength))
                            },
                            label = { FieldChromeText(stringResource(R.string.name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            label = stringResource(R.string.full_config_mode),
                            checked = useFullConfig,
                            enabled = !validating,
                        ) { enabled ->
                            if (!enabled) {
                                useFullConfig = false
                                config = config.copy(rawConfigJson = "")
                                formError = ""
                            } else {
                                validating = true
                                scope.launch {
                                    try {
                                        var candidate = config
                                        while (true) {
                                            val generatedConfig = withContext(Dispatchers.Default) {
                                                val generated = candidate.toBridgeJson(
                                                    localListenAddr = "127.0.0.1:1080",
                                                )
                                                requireSafeJsonNesting(generated)
                                                candidate.copy(
                                                    rawConfigJson = JSONObject(generated).toString(2),
                                                )
                                            }
                                            val latest = config
                                            if (latest == candidate) {
                                                config = generatedConfig
                                                useFullConfig = true
                                                formError = ""
                                                break
                                            }
                                            candidate = latest
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        val message = failure.message?.takeIf(String::isNotBlank)
                                            ?: invalidProfileMessage
                                        useFullConfig = false
                                        formError = message
                                        reportUiError(message)
                                    } finally {
                                        validating = false
                                    }
                                }
                            }
                        }
                        if (useFullConfig) {
                            Text(
                                stringResource(R.string.full_config_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = config.rawConfigJson,
                                onValueChange = { config = config.copy(rawConfigJson = it.take(MaxProfileImportLength)) },
                                label = { FieldChromeText(stringResource(R.string.full_config_json)) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 18,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            val selectedSecurity = when {
                                config.tunnelSecurity.equals("reality", ignoreCase = true) -> "reality"
                                config.tls -> "tls"
                                else -> "none"
                            }
                            val isReality = selectedSecurity == "reality"
                            val carrierOptions = when {
                                config.protocol != "native" -> listOf("tcp")
                                isReality -> listOf("tcp", "auto", "quic")
                                selectedSecurity == "tls" -> listOf("tcp", "quic")
                                else -> listOf("tcp")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.serverHost,
                                    onValueChange = {
                                        config = config.copy(serverHost = it.take(MaxProfileHostInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.server_address)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.serverPort,
                                    onValueChange = {
                                        config = config.copy(serverPort = it.filter(Char::isDigit).take(5))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.port)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.52f),
                                )
                            }
                            ChoiceRow(
                                stringResource(R.string.protocol),
                                config.protocol,
                                AppConfig.Protocols,
                            ) {
                                config = config.copy(
                                    protocol = it,
                                    carrierMode = if (it == "native") config.carrierMode else "tcp",
                                    carrierUdpMode = if (it == "native") config.carrierUdpMode else "",
                                )
                                if (it != "native") {
                                    config = config.withoutResumableMux().withoutEch().copy(
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    )
                                }
                            }
                            ChoiceRow(
                                stringResource(R.string.field_transport),
                                config.transport,
                                AppConfig.Transports,
                                enabled = !isReality,
                            ) {
                                config = config.copy(transport = it)
                                if (it != "raw") {
                                    config = config.withoutResumableMux().withoutEch()
                                }
                            }
                            OutlinedTextField(
                                value = config.token,
                                onValueChange = { config = config.copy(token = it.take(MaxProfileUriLength)) },
                                label = { FieldChromeText(stringResource(R.string.field_token)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.sni,
                                    onValueChange = {
                                        config = config.copy(sni = it.take(MaxProfileHostInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_sni)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.path,
                                    onValueChange = { config = config.copy(path = it.take(MaxProfileUriLength)) },
                                    label = { FieldChromeText(stringResource(R.string.field_path)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            ChoiceRow(
                                stringResource(R.string.field_security),
                                selectedSecurity,
                                AppConfig.SecurityOptions,
                            ) { security ->
                                config = when (security) {
                                    "tls" -> config.copy(
                                        tunnelSecurity = "",
                                        tls = true,
                                        carrierMode = config.carrierMode.takeIf { it == "quic" } ?: "tcp",
                                        carrierUdpMode = config.carrierUdpMode.takeIf {
                                            config.carrierMode == "quic"
                                        }.orEmpty(),
                                    ).withoutResumableMux().withoutEch()
                                    "reality" -> config.copy(
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        realityFingerprint = config.realityFingerprint.ifBlank { "chrome" },
                                        carrierMode = if (config.protocol == "native" && config.mux) {
                                            config.carrierMode.takeIf { it in AppConfig.CarrierModes && it != "tcp" }
                                                ?: "auto"
                                        } else {
                                            "tcp"
                                        },
                                    ).withoutEch()
                                    else -> config.copy(
                                        tunnelSecurity = "",
                                        tls = false,
                                        tlsInsecure = false,
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    ).withoutResumableMux()
                                }
                            }
                            OutlinedTextField(
                                value = config.flow,
                                onValueChange = {
                                    config = config.copy(flow = it.take(MaxProfileChoiceInputLength))
                                },
                                label = { FieldChromeText(stringResource(R.string.field_flow)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (isReality) {
                                OutlinedTextField(
                                    value = config.realityPublicKey,
                                    onValueChange = {
                                        config = config.copy(realityPublicKey = it.take(MaxRealityKeyInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_reality_public_key)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.realityFingerprint,
                                        onValueChange = {
                                            config = config.copy(
                                                realityFingerprint = it.take(MaxProfileChoiceInputLength),
                                            )
                                        },
                                        label = { FieldChromeText(stringResource(R.string.field_fingerprint)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.realityShortId,
                                        onValueChange = {
                                            config = config.copy(
                                                realityShortId = it.take(MaxProfileChoiceInputLength),
                                            )
                                        },
                                        label = { FieldChromeText(stringResource(R.string.field_short_id)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (isReality) {
                                OutlinedTextField(
                                    value = config.realitySpiderX,
                                    onValueChange = {
                                        config = config.copy(realitySpiderX = it.take(MaxProfileUriLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_spider_x)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (selectedSecurity == "tls") {
                                ToggleRow(stringResource(R.string.field_tls_insecure), config.tlsInsecure) {
                                    config = config.copy(tlsInsecure = it)
                                }
                            }
                            val canEnableEch =
                                config.protocol == "native" &&
                                    config.transport == "raw" &&
                                    selectedSecurity == "none" &&
                                    config.carrierMode.ifBlank { "tcp" } == "tcp" &&
                                    !config.muxResume
                            ToggleRow(
                                stringResource(R.string.field_ech_client_hello),
                                config.echEnabled,
                                enabled = canEnableEch || config.echEnabled,
                            ) { enabled ->
                                config = if (enabled) {
                                    config.withoutResumableMux().copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "",
                                        tls = false,
                                        tlsInsecure = false,
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        echEnabled = true,
                                        echPorts = config.echPorts.ifBlank { DefaultEchPorts },
                                    )
                                } else {
                                    config.withoutEch()
                                }
                            }
                            if (config.echEnabled) {
                                Text(
                                    stringResource(R.string.ech_client_hello_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = config.echPublicName,
                                    onValueChange = {
                                        config = config.copy(
                                            echPublicName = it.take(MaxProfileHostInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_public_name))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = config.echPublicKey,
                                    onValueChange = {
                                        config = config.copy(
                                            echPublicKey = it.take(MaxEchKeyInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_public_key))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = config.echPorts,
                                    onValueChange = {
                                        config = config.copy(
                                            echPorts = it
                                                .filter { char ->
                                                    char.isDigit() || char == ',' || char.isWhitespace()
                                                }
                                                .take(MaxProfileChoiceInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_ports))
                                    },
                                    supportingText = {
                                        FieldChromeText(stringResource(R.string.field_ech_ports_hint))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            ToggleRow(
                                stringResource(R.string.field_mux),
                                config.mux,
                            ) {
                                config = config.copy(mux = it)
                                if (!it) {
                                    config = config.withoutResumableMux().copy(
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        muxMaxSessions = 0,
                                        muxMaxStreamsPerSession = 0,
                                        muxWarmSpare = 0,
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    )
                                }
                            }
                            ChoiceRow(
                                stringResource(R.string.field_carrier_mode),
                                config.carrierMode.ifBlank { "tcp" },
                                carrierOptions,
                            ) { mode ->
                                config = when (mode) {
                                    "auto" -> config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        realityFingerprint = config.realityFingerprint.ifBlank { "chrome" },
                                        mux = true,
                                        carrierMode = "auto",
                                    ).withoutEch()
                                    "quic" -> config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tls = !isReality,
                                        mux = true,
                                        carrierMode = "quic",
                                        carrierUdpMode = config.carrierUdpMode.ifBlank { "auto" },
                                        realityFingerprint = if (isReality) {
                                            config.realityFingerprint.ifBlank { "chrome" }
                                        } else {
                                            config.realityFingerprint
                                        },
                                    ).withoutResumableMux().withoutEch()
                                    else -> config.copy(
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    ).withoutResumableMux()
                                }
                            }
                            if (config.mux && config.carrierMode in setOf("quic", "auto")) {
                                ChoiceRow(
                                    stringResource(R.string.field_carrier_udp_mode),
                                    config.carrierUdpMode.ifBlank { "reliable" },
                                    listOf("reliable", "auto", "datagram"),
                                ) { mode -> config = config.copy(carrierUdpMode = mode) }
                            }
                            if (config.mux) {
                                val effectiveMaxSessions = config.muxMaxSessions.takeIf { it > 0 } ?: 4
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.muxMaxSessions
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxMaxSessions = value
                                                    .filter(Char::isDigit)
                                                    .take(2)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_max_sessions))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_max_sessions_hint),
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = config.muxMaxSessions !in 0..32,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.muxWarmSpare
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxWarmSpare = value
                                                    .filter(Char::isDigit)
                                                    .take(2)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_warm_spares))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(
                                                    R.string.field_mux_warm_spares_hint,
                                                    effectiveMaxSessions - 1,
                                                ),
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = config.muxWarmSpare !in 0 until effectiveMaxSessions,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                OutlinedTextField(
                                    value = config.muxMaxStreamsPerSession
                                        .takeIf { it > 0 }
                                        ?.toString()
                                        .orEmpty(),
                                    onValueChange = { value ->
                                        config = config.copy(
                                            muxMaxStreamsPerSession = value
                                                .filter(Char::isDigit)
                                                .take(4)
                                                .toIntOrNull()
                                                ?: 0,
                                        )
                                    },
                                    label = {
                                        FieldChromeText(
                                            stringResource(R.string.field_mux_max_streams_per_session),
                                        )
                                    },
                                    supportingText = {
                                        FieldChromeText(
                                            stringResource(
                                                R.string.field_mux_max_streams_per_session_hint,
                                            ),
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = config.muxMaxStreamsPerSession !in 0..4096,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val canResumeMux =
                                config.mux &&
                                    config.protocol == "native" &&
                                    config.transport == "raw" &&
                                    selectedSecurity == "reality" &&
                                    config.carrierMode.equals("auto", ignoreCase = true)
                            ToggleRow(
                                stringResource(R.string.field_mux_resume),
                                config.muxResume,
                                enabled = canResumeMux || config.muxResume,
                            ) { enabled ->
                                config = if (enabled) {
                                    config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        mux = true,
                                        carrierMode = "auto",
                                        muxResume = true,
                                    ).withoutEch()
                                } else {
                                    config.withoutResumableMux()
                                }
                            }
                            if (config.muxResume) {
                                Text(
                                    stringResource(R.string.mux_resume_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.muxResumeTimeoutMillis
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxResumeTimeoutMillis = value
                                                    .filter(Char::isDigit)
                                                    .take(6)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_resume_timeout))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_resume_timeout_hint),
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.muxResumeBufferSize
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxResumeBufferSize = value
                                                    .filter(Char::isDigit)
                                                    .take(8)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_resume_buffer))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_resume_buffer_hint),
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.native_tun_capabilities_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(
    title: String,
    onBack: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
) {
    AppTopBar(
        title = title,
        onBack = onBack,
        actions = {
            FilledTonalButton(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    options: List<String>,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { FieldChromeText(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MenuShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.running_logs)) },
        text = {
            val scrollState = rememberScrollState()
            val noLogs = stringResource(R.string.no_logs)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                shape = CardShapeCompact,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            vpnState.logs.joinToString("\n").ifBlank { noLogs },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        dismissButton = {
            TextButton(onClick = TcptunState::clearLogs) {
                Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private fun shareProfile(context: Context, uri: String) {
    runRecoverableCatching {
        context.startActivity(
            Intent.createChooser(createProfileShareIntent(uri), context.getString(R.string.share_profile)),
        )
    }.onFailure { error ->
        reportUiError(error.message ?: context.getString(R.string.share_profile_failed))
    }
}

internal fun createProfileShareIntent(profile: AppConfig): Intent {
    val uri = requireNotNull(ProfileUriCodec.encode(profile)) { "profile cannot be encoded as a URI" }
    return createProfileShareIntent(uri)
}

private fun createProfileShareIntent(uri: String): Intent {
    require(uri.isNotBlank() && uri.length <= MaxProfileUriLength) { "invalid profile URI" }
    return Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, uri)
}

private data class TcpingTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
)

private fun clipboardText(context: Context): Result<String> = runRecoverableCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return@runRecoverableCatching ""
    val clip = clipboard.primaryClip ?: return@runRecoverableCatching ""
    if (clip.itemCount == 0) return@runRecoverableCatching ""
    val item = clip.getItemAt(0)
    item.text?.let { value ->
        require(value.length <= MaxProfileImportLength) { "clipboard profile data is too large" }
        return@runRecoverableCatching value.toString()
    }
    val uri = item.uri ?: return@runRecoverableCatching ""
    val scheme = uri.scheme?.lowercase().orEmpty()
    require(scheme in setOf("content", "android.resource", "file")) { "unsupported clipboard URI" }
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("clipboard URI could not be opened")
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val output = StringBuilder(minOf(MaxProfileImportLength, 4_096))
        val buffer = CharArray(4_096)
        while (true) {
            val remaining = MaxProfileImportLength + 1 - output.length
            if (remaining <= 0) error("clipboard profile data is too large")
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val character = reader.read()
                if (character < 0) break
                output.append(character.toChar())
            } else {
                output.append(buffer, 0, count)
            }
        }
        require(output.length <= MaxProfileImportLength) { "clipboard profile data is too large" }
        output.toString()
    }
}

private fun clearClipboardText(context: Context, expectedText: String) {
    runRecoverableCatching {
        val currentValue = clipboardText(context).getOrNull() ?: return@runRecoverableCatching
        if (currentValue.trim() != expectedText) return@runRecoverableCatching
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@runRecoverableCatching

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }.onFailure { error ->
        TcptunState.appendLog("clipboard clear failed: ${error.message ?: error.javaClass.simpleName}")
    }
}

internal fun readUiRuntimeSettings(context: Context): RuntimeSettings {
    return runRecoverableCatching { TcptunVpnService.readRuntimeSettings(context) }
        .getOrElse { error ->
            TcptunState.appendLog(
                "runtime settings read failed: ${error.message ?: error.javaClass.simpleName}",
            )
            RuntimeSettings()
        }
}

private fun writeUiRuntimeSettings(context: Context, settings: RuntimeSettings): Result<Unit> {
    return runRecoverableCatching { TcptunVpnService.writeRuntimeSettings(context, settings) }
}

private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return runRecoverableCatching {
        ContextCompat.checkSelfPermission(context, PostNotificationsPermission) !=
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)
}

private fun startVpn(context: Context, plan: ProfileRunPlan) {
    TcptunState.setStatus("Starting")
    TcptunState.setConnectionsReady(false)
    TcptunState.appendLog("start requested")
    enqueueVpnPlanCommand(
        context = context,
        plan = plan,
        updateOnly = false,
        onDispatchFailure = { message ->
            TcptunState.errorIfStatus("Starting", message)
            reportUiError(message)
            try {
                rollbackInitialStartAfterDispatchFailure(context.applicationContext, plan)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog(
                    "failed VPN start rollback failed: ${failureDescription(error)}",
                )
            }
        },
    )
}

/**
 * A foreground-service dispatch can fail after UI state was committed (for
 * example process/background restrictions). Roll back only when the exact
 * plan is still authoritative; a newer profile mutation always wins.
 */
private suspend fun rollbackInitialStartAfterDispatchFailure(
    context: Context,
    plan: ProfileRunPlan,
): Boolean = withContext(NonCancellable) {
    ProcessProfileMutationMutex.withLock {
        repeat(MaxProfileMutationAttempts) {
            val snapshot = withContext(Dispatchers.IO) { ProfileStore.snapshot(context) }
            val current = snapshot.requireAuthoritativeState()
            if (!shouldRollbackFailedInitialStart(current, plan)) return@withLock false
            val restored = withContext(Dispatchers.IO) {
                ProfileStore.saveIfCurrent(
                    context = context,
                    expected = snapshot,
                    next = current.copy(activeIds = emptySet()),
                ).getOrThrow()
            }
            if (restored != null) {
                TcptunState.notifyProfileStateChanged()
                TcptunState.appendLog("active profiles rolled back after VPN start dispatch failed")
                return@withLock true
            }
        }
        throw IllegalStateException("profile state changed repeatedly while rolling back failed VPN start")
    }
}

private fun stopVpn(context: Context): Boolean {
    val previous = TcptunState.state.value
    var dispatched = true
    VpnPlanCommandGeneration.incrementAndGet()
    VpnPlanCommandJob.getAndSet(null)?.cancel()
    TcptunState.setStatus("Stopping")
    TcptunState.setConnectionsReady(false)
    TcptunState.appendLog("stop requested")
    runRecoverableCatching {
        context.startService(TcptunVpnService.stopIntent(context))
    }.onFailure { err ->
        dispatched = false
        val message = err.message ?: context.getString(R.string.stop_failed)
        reportUiError(message)
        TcptunState.restoreCommandStateIfStatus(
            expectedStatus = "Stopping",
            restoredStatus = previous.status,
            restoredConnectionsReady = previous.connectionsReady,
            restoredLastError = previous.lastError,
        )
    }
    return dispatched
}

private suspend fun updateVpnOutbounds(
    context: Context,
    plan: ProfileRunPlan,
): Boolean {
    // Disable TCPing until StartOutbound/StopOutbound finishes for every changed link.
    val previousReady = TcptunState.state.value.connectionsReady
    TcptunState.markConnectionsBusy("connection update requested")
    return try {
        val appContext = context.applicationContext ?: context
        val intent = withContext(Dispatchers.IO) {
            TcptunVpnService.updateOutboundsIntent(appContext, plan)
        }
        appContext.startService(intent)
        true
    } catch (cancelled: CancellationException) {
        TcptunState.restoreConnectionsReadyIfStatus("Running", previousReady)
        throw cancelled
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        reportUiError(error.message ?: context.getString(R.string.start_failed))
        TcptunState.restoreConnectionsReadyIfStatus("Running", previousReady)
        false
    }
}

private fun enqueueVpnPlanCommand(
    context: Context,
    plan: ProfileRunPlan,
    updateOnly: Boolean,
    onDispatchSuccess: () -> Unit = {},
    onDispatchFailure: suspend (String) -> Unit,
) {
    val appContext = context.applicationContext ?: context
    val generation = VpnPlanCommandGeneration.incrementAndGet()
    val job = VpnPlanCommandScope.launch {
        val intent = try {
            withContext(Dispatchers.IO) {
                if (updateOnly) {
                    TcptunVpnService.updateOutboundsIntent(appContext, plan)
                } else {
                    TcptunVpnService.startIntent(appContext, plan)
                }
            }
        } catch (_: CancellationException) {
            return@launch
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (generation == VpnPlanCommandGeneration.get()) {
                onDispatchFailure(error.message ?: appContext.getString(R.string.start_failed))
            }
            return@launch
        }
        if (generation != VpnPlanCommandGeneration.get()) return@launch
        try {
            if (!updateOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            if (generation == VpnPlanCommandGeneration.get()) onDispatchSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (generation == VpnPlanCommandGeneration.get()) {
                onDispatchFailure(error.message ?: appContext.getString(R.string.start_failed))
            }
        }
    }
    VpnPlanCommandJob.getAndSet(job)?.cancel()
}

private fun applyRuntimeSettings(context: Context, forceRestart: Boolean = false) {
    val status = TcptunState.status
    if (status != "Starting" && status != "Running") return
    runRecoverableCatching {
        context.startService(
            TcptunVpnService.applyRuntimeSettingsIntent(context, forceRestart = forceRestart),
        )
    }.onFailure { err ->
        TcptunState.appendLog("runtime settings apply request failed: ${err.message}")
    }
}

private fun applyFlowAnalysisSettings(context: Context) {
    val status = TcptunState.status
    if (status != "Starting" && status != "Running") return
    runRecoverableCatching {
        context.startService(TcptunVpnService.updateFlowAnalysisIntent(context))
    }.onFailure { err ->
        TcptunState.appendLog("flow analysis update request failed: ${err.message}")
    }
}

private fun isVpnActiveStatus(status: String): Boolean {
    return status == "Starting" || status == "Running" || status == "Stopping"
}

internal fun nextActiveProfileIds(
    activeIds: Set<String>,
    profileId: String,
    vpnStatus: String,
): Set<String> {
    val profileIsRunning = profileId in activeIds && isVpnActiveStatus(vpnStatus)
    return if (profileIsRunning) activeIds - profileId else activeIds + profileId
}

internal fun rollbackProfileStateIfStillCommitted(
    current: ProfilesState,
    currentRevision: Long,
    committed: ProfilesState,
    committedRevision: Long,
    previous: ProfilesState,
): ProfilesState? = previous.takeIf {
    currentRevision == committedRevision && current == committed
}

internal fun shouldRollbackFailedInitialStart(
    current: ProfilesState,
    failedPlan: ProfileRunPlan,
): Boolean = current.activeIds.isNotEmpty() &&
    runRecoverableCatching { current.runPlan() == failedPlan }.getOrDefault(false)

internal fun canStartTcping(
    status: String,
    activeProfileCount: Int,
    connectionsReady: Boolean = true,
): Boolean = status == "Running" && activeProfileCount > 0 && connectionsReady

private fun isVpnTransitionStatus(status: String): Boolean {
    return status == "Starting" || status == "Stopping"
}

@Composable
private fun vpnStatusLabel(status: String): String {
    return when (status) {
        "Starting" -> stringResource(R.string.status_starting)
        "Running" -> stringResource(R.string.status_running)
        "Stopping" -> stringResource(R.string.status_stopping)
        else -> status
    }
}

internal fun bridgeTimestampLabel(timestampMs: Long, noneLabel: String): String {
    if (timestampMs <= 0) return noneLabel
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.MEDIUM,
    ).format(java.util.Date(timestampMs))
}

private val TCPING_TARGETS = listOf(
    TcpingTarget("Google", "google.com"),
    TcpingTarget("GitHub", "github.com"),
    TcpingTarget("Cloudflare", "cloudflare.com"),
)
