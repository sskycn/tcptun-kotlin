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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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

internal data class NetworkLinkInfo(
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

internal fun readLocalIpInfo(
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

internal fun readLocalIpInfoSafely(
    connectivity: ConnectivityManager,
    networks: Collection<Network>,
    tetheredInterfaceNames: Set<String>?,
): LocalIpInfo = runRecoverableCatching {
    readLocalIpInfo(connectivity, networks, tetheredInterfaceNames)
}.getOrDefault(LocalIpInfo())

internal fun formatDefaultGatewayIpv4(linkProperties: LinkProperties?): String {
    return linkProperties?.routes.orEmpty()
        .asSequence()
        .filter { it.isDefaultRoute }
        .mapNotNull { route -> route.gateway?.takeIf { it is Inet4Address }?.hostAddress }
        .distinct()
        .joinToString("\n")
}

internal fun formatIpAddresses(linkProperties: LinkProperties?, ipv6: Boolean): String {
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

internal fun hostFromListenAddress(address: String): String {
    val value = address.trim().substringBefore(',').trim()
    if (value.startsWith('[')) return value.substringAfter('[').substringBefore(']').trim()
    return when (value.count { it == ':' }) {
        0 -> value
        1 -> value.substringBeforeLast(':').trim()
        else -> value
    }
}

internal fun portFromListenAddress(address: String): String {
    val value = address.trim().substringBefore(',').trim()
    if (value.startsWith('[')) return value.substringAfter(']').removePrefix(":").trim()
    return if (value.count { it == ':' } == 1) value.substringAfterLast(':').trim() else ""
}

internal fun formatHostPort(host: String, port: String): String {
    if (host.isBlank() || port.isBlank()) return host
    return if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"
}

@RequiresApi(36)
internal fun registerTetheringInterfaceCallback(
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
    if (TcptunState.status != VpnStatus.Running) return
    TcptunVpnService.requestUiVisibleHealthCheck()
    delay(PullRefreshSettleMillis)
}

