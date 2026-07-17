package com.tcptun.client

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.ContextCompat
import com.tcptun.client.ui.theme.TcpTunTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SnackbarAutoDismissMillis = 6_000L
private const val HealthUnknownAnimationIntervalMillis = 500L

private val CardShape = RoundedCornerShape(16.dp)
private val CardShapeCompact = RoundedCornerShape(12.dp)
private val MenuShape = RoundedCornerShape(12.dp)
private val ListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val ListItemSpacing = 8.dp

private data class LocalIpInfo(
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
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val linkProperties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        NetworkLinkInfo(network, capabilities, linkProperties)
    }
    val activeNetwork = connectivity.activeNetwork
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
    val manager = requireNotNull(context.getSystemService(TetheringManager::class.java))
    val callback = object : TetheringManager.TetheringEventCallback {
        override fun onTetheredInterfacesChanged(interfaces: Set<android.net.TetheringInterface>) {
            val wifiInterfaces = interfaces
                .filter { it.type == TetheringManager.TETHERING_WIFI }
                .mapTo(linkedSetOf()) { it.`interface` }
            onChanged(wifiInterfaces)
        }
    }
    manager.registerTetheringEventCallback(context.mainExecutor, callback)
    return { manager.unregisterTetheringEventCallback(callback) }
}

@Composable
private fun rememberLocalIpInfo(context: Context): LocalIpInfo {
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val initialNetworks = listOfNotNull(connectivity.activeNetwork)
    val initialTetheredInterfaces: Set<String>? = if (Build.VERSION.SDK_INT >= 36) emptySet() else null
    val info by produceState(
        initialValue = readLocalIpInfo(connectivity, initialNetworks, initialTetheredInterfaces),
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
                val next = readLocalIpInfo(connectivity, snapshot, tetheredSnapshot)
                val isCurrent = synchronized(observedNetworksLock) { sequence == refreshSequence }
                if (isCurrent) value = next
            }
        }
        fun refresh(network: Network, available: Boolean) {
            scheduleRefresh {
                if (available) add(network) else remove(network)
            }
        }
        fun refreshDefaultNetwork(network: Network) {
            scheduleRefresh {
                if (connectivity.activeNetwork == network) add(network)
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh(network, available = true)
            override fun onLost(network: Network) = refresh(network, available = false)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refresh(network, available = true)
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                refresh(network, available = true)
            }
        }
        val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshDefaultNetwork(network)
            override fun onLost(network: Network) = scheduleRefresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refreshDefaultNetwork(network)
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                refreshDefaultNetwork(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val registered = runCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess
        val defaultRegistered = runCatching {
            connectivity.registerDefaultNetworkCallback(defaultNetworkCallback)
        }.isSuccess
        val unregisterTetheringCallback = if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                registerTetheringInterfaceCallback(context) { wifiInterfaces ->
                    scheduleRefresh(nextTetheredInterfaceNames = wifiInterfaces)
                }
            }.getOrNull()
        } else {
            null
        }
        launch {
            while (true) {
                delay(2_000)
                scheduleRefresh()
            }
        }
        awaitDispose {
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            if (defaultRegistered) runCatching { connectivity.unregisterNetworkCallback(defaultNetworkCallback) }
            unregisterTetheringCallback?.let { unregister -> runCatching(unregister) }
        }
    }
    return info
}

class MainActivity : ComponentActivity() {
    private var profileIntentSequence = 0L
    private var pendingProfileUri by mutableStateOf<PendingProfileUri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TcpTun)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleProfileIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileIntent(intent)
    }

    private fun handleProfileIntent(intent: Intent?) {
        val value = profileUriFromIntent(intent) ?: return
        pendingProfileUri = PendingProfileUri(++profileIntentSequence, value)
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
    var state by remember { mutableStateOf(ProfileStore.load(context)) }
    var pendingDeepLinkProfile by remember { mutableStateOf<AppConfig?>(null) }
    var pendingConfig by remember { mutableStateOf<ProfileRunPlan?>(null) }
    var pendingNotificationConfig by remember { mutableStateOf<ProfileRunPlan?>(null) }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showIpInformation by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRouteManagement by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var profileQrCode by remember { mutableStateOf<AppConfig?>(null) }
    var tcpingTargetIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val vpnState by TcptunState.state.collectAsState()
    LaunchedEffect(vpnState.status) {
        if (vpnState.status == "Stopped" || vpnState.status == "Error") {
            delay(100)
            state = ProfileStore.load(context)
        }
    }
    LaunchedEffect(vpnState.profileStateRevision) {
        if (vpnState.profileStateRevision > 0) state = ProfileStore.load(context)
    }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingConfig?.let {
            startVpn(context, it)
            pendingConfig = null
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNotificationConfig?.let { plan ->
            pendingNotificationConfig = null
            if (!granted) {
                TcptunState.appendLog("notification permission denied; foreground notification may be hidden")
            }
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                pendingConfig = plan
                vpnLauncher.launch(prepare)
            } else {
                startVpn(context, plan)
            }
        }
    }
    fun save(next: ProfilesState) {
        ProfileStore.save(context, next)
        state = ProfileStore.load(context)
    }

    fun importValidatedProfile(profile: AppConfig): Pair<AppConfig, Boolean> {
        validateImportedProfile(profile)
        val identity = requireNotNull(profileConnectionIdentity(profile))
        val existing = state.profiles.firstOrNull { current ->
            profileConnectionIdentity(current) == identity
        }
        return if (existing == null) {
            save(state.copy(profiles = state.profiles + profile))
            profile to true
        } else {
            existing to false
        }
    }

    LaunchedEffect(pendingProfileUri?.sequence) {
        val pending = pendingProfileUri ?: return@LaunchedEffect
        try {
            val profile = ProfileUriCodec.decode(pending.value).getOrThrow()
            if (ProfileDeepLinkCodec.isSupportedLink(pending.value)) {
                validateImportedProfile(profile)
                pendingDeepLinkProfile = profile
            } else {
                val (storedProfile, added) = importValidatedProfile(profile)
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
        val link = clipboardText(context).trim()
        if (link.isBlank()) {
            TcptunState.error(emptyClipboard)
            return
        }
        runCatching {
            val profile = ProfileUriCodec.decode(link).getOrThrow()
            importValidatedProfile(profile)
        }.fold(
            onSuccess = {
                clearClipboardText(context, link)
            },
            onFailure = { TcptunState.error(invalidClipboard) },
        )
    }

    fun importScannedProfile(link: String): Boolean {
        return runCatching {
            val profile = ProfileUriCodec.decode(link.trim()).getOrThrow()
            importValidatedProfile(profile)
        }.fold(
            onSuccess = { (storedProfile, added) ->
                showQrScanner = false
                screenScope.launch {
                    val message = if (added) {
                        resources.getString(R.string.profile_imported, storedProfile.name)
                    } else {
                        resources.getString(R.string.profile_already_exists, storedProfile.name)
                    }
                    snackbarHostState.showDismissibleSnackbar(message)
                }
                true
            },
            onFailure = { false },
        )
    }

    fun launchPlan(plan: ProfileRunPlan) {
        TcptunState.clearLogs()
        if (needsNotificationPermission(context)) {
            pendingNotificationConfig = plan
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            pendingConfig = plan
            vpnLauncher.launch(prepare)
        } else {
            startVpn(context, plan)
        }
    }

    fun applyRunningState(nextState: ProfilesState) {
        if (nextState.activeIds.isEmpty()) {
            save(nextState)
            stopVpn(context)
            return
        }
        val plan = runCatching { nextState.runPlan() }.getOrElse { err ->
            TcptunState.error(err.message ?: resources.getString(R.string.start_failed))
            showLogs = true
            return
        }
        TcptunState.clearTcping()
        val canUpdateOutbounds = vpnState.status == "Running" && state.activeIds.isNotEmpty()
        save(nextState)
        if (canUpdateOutbounds) {
            updateVpnOutbounds(context, plan)
        } else {
            launchPlan(plan)
        }
    }

    fun toggleProfile(profile: AppConfig) {
        if (isVpnTransitionStatus(vpnState.status)) return
        val nextActiveIds = nextActiveProfileIds(
            activeIds = state.activeIds,
            profileId = profile.id,
            vpnStatus = vpnState.status,
        )
        applyRunningState(state.copy(activeIds = nextActiveIds))
    }

    fun deleteProfile(profile: AppConfig) {
        val profileIndex = state.profiles.indexOfFirst { it.id == profile.id }
        if (profileIndex < 0) return
        val remaining = state.profiles.toMutableList().also { it.removeAt(profileIndex) }
        val wasActive = profile.id in state.activeIds
        val nextState = state.copy(profiles = remaining, activeIds = state.activeIds - profile.id)
        if (wasActive) applyRunningState(nextState) else save(nextState)
        screenScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showDismissibleSnackbar(
                message = resources.getString(R.string.profile_deleted, profile.name),
                actionLabel = undoLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val current = ProfileStore.load(context)
                if (current.profiles.none { it.id == profile.id }) {
                    val restored = current.profiles.toMutableList()
                    restored.add(profileIndex.coerceAtMost(restored.size), profile)
                    val restoredState = current.copy(
                        profiles = restored,
                        activeIds = if (wasActive) current.activeIds + profile.id else current.activeIds,
                    )
                    if (wasActive) applyRunningState(restoredState) else save(restoredState)
                }
            }
        }
    }

    val editing = editingProfile
    if (showQrScanner) {
        QrScannerPage(
            onBack = { showQrScanner = false },
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
    } else if (showRouteManagement) {
        RouteManagementPage(
            onBack = { showRouteManagement = false },
        )
    } else if (editing == null) {
        val listIpInfo = rememberLocalIpInfo(context)
        val configuredListenAddress = TcptunVpnService.localSocksListenAddr(
            TcptunVpnService.readRuntimeSettings(context),
        )
        val effectiveListenAddress = vpnState.diagnostics.bridgeListen
            .takeIf { vpnState.status == "Running" }
            .orEmpty()
            .ifBlank { configuredListenAddress }
        val proxyAccess = proxyAccessDisplay(
            listenAddress = effectiveListenAddress,
            hotspotIpv4 = listIpInfo.hotspotIpv4,
            underlyingIpv4 = listIpInfo.underlyingIpv4,
            proxyRunning = vpnState.status == "Running",
        )
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(
                    title = stringResource(R.string.profiles_title),
                    onRouteManagement = { showRouteManagement = true },
                    onSettings = { showSettings = true },
                )
            },
            snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
            floatingActionButton = {
                MainActionsFab(
                    onImport = ::importFromClipboard,
                    onScan = { showQrScanner = true },
                )
            },
            bottomBar = {
                val tcpingEnabled = canStartTcping(vpnState.status, state.activeProfiles.size)
                BottomStatus(
                    status = vpnState.status,
                    error = vpnState.lastError,
                    tcping = vpnState.tcping,
                    hasProfiles = state.profiles.isNotEmpty(),
                    tcpingEnabled = tcpingEnabled,
                    onClick = {
                        if (!tcpingEnabled || vpnState.tcping.running) return@BottomStatus
                        val tcpingTarget = TCPING_TARGETS[tcpingTargetIndex]
                        tcpingTargetIndex = (tcpingTargetIndex + 1) % TCPING_TARGETS.size
                        val requestId = TcptunState.beginTcping(
                            targetLabel = tcpingTarget.label,
                            total = state.activeProfiles.size,
                        )
                        runCatching {
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                    ProfileRow(
                        profile = profile,
                        running = profile.id in state.activeIds && isVpnActiveStatus(vpnState.status),
                        health = vpnState.profileHealth[profile.id],
                        enabled = !isVpnTransitionStatus(vpnState.status),
                        onClick = { toggleProfile(profile) },
                        shareable = ProfileUriCodec.encode(profile) != null,
                        onShare = { shareProfile(context, profile) },
                        onShowQrCode = { profileQrCode = profile },
                        onDeleteRequest = { deleteProfile(profile) },
                    )
                }
                if (state.profiles.isEmpty()) {
                    item {
                        EmptyState(onAdd = { editingProfile = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") })
                    }
                }
            }
        }
    } else {
        EditProfilePage(
            initial = editing,
            onBack = { editingProfile = null },
            onSave = { updated ->
                val profiles = state.profiles.toMutableList()
                val index = profiles.indexOfFirst { it.id == updated.id }
                if (index >= 0) {
                    profiles[index] = updated
                } else {
                    profiles.add(updated)
                }
                val nextState = state.copy(profiles = profiles)
                if (updated.id in state.activeIds && vpnState.status == "Running") {
                    applyRunningState(nextState)
                } else {
                    save(nextState)
                }
                editingProfile = null
            },
        )
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
            onConfirm = {
                pendingDeepLinkProfile = null
                val (storedProfile, added) = importValidatedProfile(profile)
                screenScope.launch {
                    val message = if (added) {
                        resources.getString(R.string.profile_imported, storedProfile.name)
                    } else {
                        resources.getString(R.string.profile_already_exists, storedProfile.name)
                    }
                    snackbarHostState.showDismissibleSnackbar(message)
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_profile_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.maskedAddress(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    profile.label(),
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
            Button(onClick = onConfirm) {
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
    onRouteManagement: () -> Unit,
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
        FloatingActionButton(onClick = { menuExpanded = true }) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.actions),
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: AppConfig,
    running: Boolean,
    health: ProfileHealth?,
    enabled: Boolean,
    shareable: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShowQrCode: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
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
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            try {
                onDeleteRequest()
            } finally {
                withContext(NonCancellable) {
                    dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                }
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.errorContainer, CardShape)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = colors.onErrorContainer,
                )
            }
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            color = rowColor,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clickable(enabled = enabled, onClick = onClick)
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
                        text = profile.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        profile.maskedAddress(),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onShare,
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
        ProfileHealthStatus.Unknown -> animatedHealthUnknownLabel()
        ProfileHealthStatus.Healthy -> health?.latencyMs?.let { latency ->
            stringResource(R.string.profile_health_latency, latency)
        }
        ProfileHealthStatus.Degraded -> {
            val failures = (health?.failures ?: 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.profile_health_degraded_failures, failures, failures)
        }
    }
}

@Composable
private fun animatedHealthUnknownLabel(): String {
    val dotCount by produceState(initialValue = 1) {
        while (true) {
            delay(HealthUnknownAnimationIntervalMillis)
            value = value % 3 + 1
        }
    }
    return ".".repeat(dotCount)
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
    val context = LocalContext.current
    val uri = remember(profile) { requireNotNull(ProfileUriCodec.encode(profile)) }
    val logo = remember(context) { ContextCompat.getDrawable(context, R.mipmap.ic_launcher) }
    val bitmap = remember(uri, logo) { generateQrCodeBitmap(uri, 768, logo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
        title = { Text(stringResource(R.string.profile_qr_code)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CardShapeCompact,
                    color = Color.White,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.profile_qr_code_description, profile.name),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(12.dp),
                    )
                }
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
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
        FilledTonalButton(onClick = onAdd) {
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
    tcpingEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tcpingMessage = tcpingStatusText(tcping)
    val text = when {
        error.isNotBlank() -> stringResource(R.string.error_prefix, error)
        tcpingMessage.isNotBlank() -> tcpingMessage
        status == "Running" && tcpingEnabled -> stringResource(R.string.connected_tap_test)
        status == "Starting" -> stringResource(R.string.connecting)
        status == "Stopping" -> stringResource(R.string.stopping)
        hasProfiles -> stringResource(R.string.not_connected_tap_profile)
        else -> stringResource(R.string.not_connected_add_profile)
    }
    val contentColor = when {
        error.isNotBlank() -> colors.error
        tcping.running -> colors.tertiary
        tcping.error.isNotBlank() && tcping.results.isEmpty() -> colors.error
        tcping.results.any { it.elapsedMs != null } || status == "Running" -> colors.primary
        status == "Starting" || status == "Stopping" -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    val statusIcon = when {
        error.isNotBlank() -> Icons.Rounded.Speed
        status == "Running" -> Icons.Rounded.Check
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
private fun DiagnosticsPage(onBack: () -> Unit, onShowLogs: () -> Unit) {
    val vpnState by TcptunState.state.collectAsState()
    val diagnostics = vpnState.diagnostics
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
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
                        DiagnosticsLine(stringResource(R.string.diag_go_state), diagnostics.bridgeEventState)
                        DiagnosticsLine(stringResource(R.string.diag_go_phase), diagnostics.bridgeEventPhase)
                        DiagnosticsLine(stringResource(R.string.diag_go_listen), diagnostics.bridgeListen.ifBlank { noneLabel })
                        DiagnosticsLine(stringResource(R.string.diag_go_remote), diagnostics.bridgeRemote.ifBlank { noneLabel })
                        DiagnosticsLine(stringResource(R.string.diag_go_active), diagnostics.bridgeActiveConnections.toString())
                        DiagnosticsLine(stringResource(R.string.diag_go_error), diagnostics.bridgeLastError.ifBlank { noneLabel })
                        DiagnosticsLine(stringResource(R.string.diag_go_event_time), bridgeTimestampLabel(diagnostics.bridgeTimestampMs, noneLabel))
                        DiagnosticsLine(
                            stringResource(R.string.diag_local_proxy),
                            "${diagnostics.localProxyAddress} · ${if (diagnostics.localProxyReachable) stringResource(R.string.reachable) else stringResource(R.string.not_reachable)}",
                        )
                        DiagnosticsLine(stringResource(R.string.diag_socket_protect), if (diagnostics.socketProtectEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.diag_health_interval), stringResource(R.string.seconds_value, diagnostics.healthCheckIntervalSeconds))
                        DiagnosticsLine(stringResource(R.string.diag_power_saving), if (diagnostics.powerSavingMode) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.recent_restart_reason), diagnostics.lastRestartReason)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

@Composable
private fun SectionTitle(
    icon: ImageVector,
    title: String,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun IpInformationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val ipInfo = rememberLocalIpInfo(context)
    val vpnState by TcptunState.state.collectAsState()
    val settings = TcptunVpnService.readRuntimeSettings(context)
    val configuredListenAddress = TcptunVpnService.localSocksListenAddr(settings)
    val actualListenAddress = vpnState.diagnostics.bridgeListen
        .takeIf { vpnState.status == "Running" }
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
        proxyRunning = vpnState.status == "Running",
    )
    val noneLabel = stringResource(R.string.none)

    LaunchedEffect(vpnState.status) {
        while (vpnState.status == "Running") {
            runCatching { context.startService(TcptunVpnService.refreshClientIpsIntent(context)) }
            delay(1_000)
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { IpInformationTopBar(onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
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
private fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(TcptunVpnService.readRuntimeSettings(context)) }
    var socksPortText by remember { mutableStateOf(settings.socksPort.toString()) }
    var probeTimeoutText by remember { mutableStateOf(settings.probeTimeout) }
    var failureThresholdText by remember { mutableStateOf(settings.failureThreshold.toString()) }
    var positiveTtlText by remember { mutableStateOf(settings.positiveTtl) }
    var negativeTtlText by remember { mutableStateOf(settings.negativeTtl) }
    var settingsDirty by remember { mutableStateOf(false) }
    val vpnState by TcptunState.state.collectAsState()
    val diagnostics = vpnState.diagnostics
    val mtuOptions = listOf("1280", "1360", "1400", "1500")

    fun saveSettings(next: RuntimeSettings) {
        val before = settings
        TcptunVpnService.writeRuntimeSettings(context, next)
        settings = TcptunVpnService.readRuntimeSettings(context)
        socksPortText = settings.socksPort.toString()
        probeTimeoutText = settings.probeTimeout
        failureThresholdText = settings.failureThreshold.toString()
        positiveTtlText = settings.positiveTtl
        negativeTtlText = settings.negativeTtl
        if (settings != before) {
            settingsDirty = true
        }
    }

    fun leaveSettings() {
        val socksPort = socksPortText.toIntOrNull()
        if (socksPort == null || socksPort !in 1..65535) return
        if (!isValidDuration(probeTimeoutText) || !isValidDuration(positiveTtlText) || !isValidDuration(negativeTtlText)) return
        val failureThreshold = failureThresholdText.toIntOrNull()
        if (failureThreshold == null || failureThreshold !in 1..TcptunVpnService.MAX_FAILURE_THRESHOLD) return
        if (settingsDirty) {
            applyRuntimeSettings(context)
            settingsDirty = false
        }
        onBack()
    }

    BackHandler(onBack = ::leaveSettings)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(onBack = ::leaveSettings)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                        ChoiceRow("MTU", settings.mtu.toString(), mtuOptions) { value ->
                            saveSettings(settings.copy(mtu = value.toIntOrNull() ?: TcptunVpnService.DEFAULT_VPN_MTU))
                        }
                        val socksPort = socksPortText.toIntOrNull()
                        OutlinedTextField(
                            value = socksPortText,
                            onValueChange = { value ->
                                val digits = value.filter { it.isDigit() }.take(5)
                                socksPortText = digits
                                val port = digits.toIntOrNull()
                                if (port != null && port in 1..65535) {
                                    saveSettings(settings.copy(socksPort = port))
                                }
                            },
                            label = { Text(stringResource(R.string.socks_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = socksPort == null || socksPort !in 1..65535,
                            supportingText = {
                                if (socksPort == null || socksPort !in 1..65535) {
                                    Text(stringResource(R.string.socks_port_error))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(stringResource(R.string.socks_listen_all), settings.socksListenAll) { checked ->
                            saveSettings(settings.copy(socksListenAll = checked))
                        }
                        ToggleRow(stringResource(R.string.route_external_sources), settings.routeExternalSources) { checked ->
                            saveSettings(settings.copy(routeExternalSources = checked))
                        }
                        ToggleRow(stringResource(R.string.direct_first), settings.directFirst) { checked ->
                            saveSettings(settings.copy(directFirst = checked))
                        }
                        val validProbeTimeout = isValidDuration(probeTimeoutText)
                        OutlinedTextField(
                            value = probeTimeoutText,
                            onValueChange = { value ->
                                probeTimeoutText = value.take(32)
                                if (isValidDuration(probeTimeoutText)) {
                                    saveSettings(settings.copy(probeTimeout = probeTimeoutText))
                                }
                            },
                            label = { Text(stringResource(R.string.probe_timeout)) },
                            singleLine = true,
                            enabled = settings.directFirst,
                            isError = settings.directFirst && !validProbeTimeout,
                            supportingText = {
                                if (settings.directFirst && !validProbeTimeout) {
                                    Text(stringResource(R.string.duration_error))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val failureThreshold = failureThresholdText.toIntOrNull()
                        OutlinedTextField(
                            value = failureThresholdText,
                            onValueChange = { value ->
                                failureThresholdText = value.filter(Char::isDigit).take(3)
                                val threshold = failureThresholdText.toIntOrNull()
                                if (threshold != null && threshold in 1..TcptunVpnService.MAX_FAILURE_THRESHOLD) {
                                    saveSettings(settings.copy(failureThreshold = threshold))
                                }
                            },
                            label = { Text(stringResource(R.string.failure_threshold)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = settings.directFirst,
                            isError = settings.directFirst && (failureThreshold == null || failureThreshold !in 1..TcptunVpnService.MAX_FAILURE_THRESHOLD),
                            supportingText = {
                                if (settings.directFirst && (failureThreshold == null || failureThreshold !in 1..TcptunVpnService.MAX_FAILURE_THRESHOLD)) {
                                    Text(stringResource(R.string.failure_threshold_error))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DurationSettingField(
                            value = positiveTtlText,
                            onValueChange = { value ->
                                positiveTtlText = value.take(32)
                                if (isValidDuration(positiveTtlText)) saveSettings(settings.copy(positiveTtl = positiveTtlText))
                            },
                            label = stringResource(R.string.positive_ttl),
                            enabled = settings.directFirst,
                        )
                        DurationSettingField(
                            value = negativeTtlText,
                            onValueChange = { value ->
                                negativeTtlText = value.take(32)
                                if (isValidDuration(negativeTtlText)) saveSettings(settings.copy(negativeTtl = negativeTtlText))
                            },
                            label = stringResource(R.string.negative_ttl),
                            enabled = settings.directFirst,
                        )
                        OutlinedTextField(
                            value = settings.socksUsername,
                            onValueChange = { value -> saveSettings(settings.copy(socksUsername = value.take(255))) },
                            label = { Text(stringResource(R.string.socks_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = settings.socksPassword,
                            onValueChange = { value -> saveSettings(settings.copy(socksPassword = value.take(255))) },
                            label = { Text(stringResource(R.string.socks_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(stringResource(R.string.enable_udp_relay), settings.udpEnabled) { checked ->
                            saveSettings(settings.copy(udpEnabled = checked))
                        }
                        ToggleRow(stringResource(R.string.power_saving_mode), settings.powerSavingMode) { checked ->
                            saveSettings(settings.copy(powerSavingMode = checked, udpEnabled = settings.udpEnabled && !checked))
                        }
                        Text(
                            stringResource(R.string.socks_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.direct_first_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.power_saving_note),
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Check,
                            title = stringResource(R.string.current_effective),
                        )
                        DiagnosticsLine("MTU", diagnostics.mtu.toString())
                        DiagnosticsLine(stringResource(R.string.socks_listen), TcptunVpnService.localSocksListenAddr(settings))
                        DiagnosticsLine(stringResource(R.string.route_external_sources), if (settings.routeExternalSources) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.direct_first), if (settings.directFirst) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.probe_timeout), settings.probeTimeout)
                        DiagnosticsLine(stringResource(R.string.failure_threshold), settings.failureThreshold.toString())
                        DiagnosticsLine(stringResource(R.string.positive_ttl), settings.positiveTtl)
                        DiagnosticsLine(stringResource(R.string.negative_ttl), settings.negativeTtl)
                        DiagnosticsLine(stringResource(R.string.socks_auth), if (settings.socksUsername.isNotEmpty() || settings.socksPassword.isNotEmpty()) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.field_udp), if (diagnostics.udpEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.diag_power_saving), if (diagnostics.powerSavingMode) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    val valid = isValidDuration(value)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = enabled && !valid,
        supportingText = {
            if (enabled && !valid) Text(stringResource(R.string.duration_error))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val profileState = remember { ProfileStore.load(context) }
    val routeProfiles = profileState.profiles.filter { it.rawConfigJson.isBlank() }
    var rules by remember { mutableStateOf(RouteRuleStore.load(context)) }
    var editingRule by remember { mutableStateOf<ManagedRouteRule?>(null) }
    var deleteCandidate by remember { mutableStateOf<ManagedRouteRule?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun persist(next: List<ManagedRouteRule>): Boolean {
        return RouteRuleStore.save(context, next).fold(
            onSuccess = {
                rules = RouteRuleStore.load(context)
                dirty = true
                error = ""
                true
            },
            onFailure = {
                error = it.message.orEmpty()
                false
            },
        )
    }

    fun leave() {
        if (dirty) applyRuntimeSettings(context)
        onBack()
    }

    BackHandler(onBack = ::leave)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.route_management),
                onBack = ::leave,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingRule = ManagedRouteRule() }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_route_rule))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    RouteRulesEmptyState(onAdd = { editingRule = ManagedRouteRule() })
                }
            }
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                ManagedRouteRuleRow(
                    rule = rule,
                    profiles = routeProfiles,
                    onClick = { editingRule = rule },
                    onEnabledChange = { enabled ->
                        persist(rules.toMutableList().also { it[index] = rule.copy(enabled = enabled) })
                    },
                    onMoveUp = {
                        if (index > 0) {
                            persist(rules.toMutableList().also { list ->
                                val previous = list[index - 1]
                                list[index - 1] = list[index]
                                list[index] = previous
                            })
                        }
                    },
                    onMoveDown = {
                        if (index < rules.lastIndex) {
                            persist(rules.toMutableList().also { list ->
                                val next = list[index + 1]
                                list[index + 1] = list[index]
                                list[index] = next
                            })
                        }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < rules.lastIndex,
                    onDeleteRequest = { deleteCandidate = rule },
                )
            }
        }
    }

    editingRule?.let { rule ->
        ManagedRouteRuleDialog(
            rule = rule,
            profiles = routeProfiles,
            isNew = rules.none { it.id == rule.id },
            onDismiss = { editingRule = null },
            onSave = { updated ->
                val index = rules.indexOfFirst { it.id == updated.id }
                val next = rules.toMutableList()
                if (index >= 0) next[index] = updated else next.add(updated)
                if (persist(next)) editingRule = null
            },
            onDeleteRequest = {
                editingRule = null
                deleteCandidate = rule
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
                    onClick = {
                        if (persist(rules.filterNot { it.id == rule.id })) deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ManagedRouteRuleRow(
    rule: ManagedRouteRule,
    profiles: List<AppConfig>,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDeleteRequest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDeleteRequest()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.errorContainer, CardShape)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = colors.onErrorContainer)
            }
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 88.dp),
            shape = CardShape,
            color = if (rule.enabled) colors.surfaceContainerLow else colors.surfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
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
                Column {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.move_rule_up))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.move_rule_down))
                    }
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        }
    }
}

@Composable
private fun RouteRulesEmptyState(onAdd: () -> Unit) {
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
        FilledTonalButton(onClick = onAdd) {
            Text(stringResource(R.string.add_route_rule))
        }
    }
}

@Composable
private fun ManagedRouteRuleDialog(
    rule: ManagedRouteRule,
    profiles: List<AppConfig>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ManagedRouteRule) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var type by remember(rule.id) { mutableStateOf(rule.type) }
    var value by remember(rule.id) { mutableStateOf(rule.value) }
    var outbound by remember(rule.id) { mutableStateOf(rule.outbound) }
    var outboundProfileId by remember(rule.id) { mutableStateOf(rule.outboundProfileId) }
    var enabled by remember(rule.id) { mutableStateOf(rule.enabled) }
    var invalid by remember(rule.id) { mutableStateOf(false) }
    val types = ManagedRouteRuleType.entries
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_route_rule else R.string.edit_route_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceRow(stringResource(R.string.route_rule_type), typeLabels[type.ordinal], typeLabels) { selected ->
                    type = types[typeLabels.indexOf(selected).coerceAtLeast(0)]
                    invalid = false
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.route_rule_value)) },
                    supportingText = {
                        Text(
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
            Row {
                if (!isNew) {
                    TextButton(onClick = onDeleteRequest) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
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
    },
)

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IpInformationTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.ip_information),
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.settings),
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            titleContentColor = colors.onBackground,
            navigationIconContentColor = colors.onSurface,
            actionIconContentColor = colors.onSurfaceVariant,
        ),
    )
}

@Composable
private fun DiagnosticsLine(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(0.46f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(0.54f),
            textAlign = TextAlign.End,
        )
    }
}


@Composable
private fun EditProfilePage(initial: AppConfig, onBack: () -> Unit, onSave: (AppConfig) -> Unit) {
    var config by remember(initial.id) { mutableStateOf(initial) }
    var useFullConfig by remember(initial.id) { mutableStateOf(initial.rawConfigJson.isNotBlank()) }
    var formError by remember(initial.id) { mutableStateOf("") }

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
                onSave = {
                    val error = config.validate()
                    if (error != null) {
                        formError = error
                        TcptunState.error(error)
                    } else {
                        onSave(config)
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
                            onValueChange = { config = config.copy(name = it) },
                            label = { Text(stringResource(R.string.name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(stringResource(R.string.full_config_mode), useFullConfig) { enabled ->
                            useFullConfig = enabled
                            config = if (enabled) {
                                val generated = config.toBridgeJson(localListenAddr = "127.0.0.1:1080")
                                config.copy(rawConfigJson = JSONObject(generated).toString(2))
                            } else {
                                config.copy(rawConfigJson = "")
                            }
                            formError = ""
                        }
                        if (useFullConfig) {
                            Text(
                                stringResource(R.string.full_config_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = config.rawConfigJson,
                                onValueChange = { config = config.copy(rawConfigJson = it.take(MAX_FULL_CONFIG_LENGTH)) },
                                label = { Text(stringResource(R.string.full_config_json)) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 18,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.serverHost,
                                    onValueChange = { config = config.copy(serverHost = it) },
                                    label = { Text(stringResource(R.string.server_address)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.serverPort,
                                    onValueChange = { config = config.copy(serverPort = it.filter(Char::isDigit)) },
                                    label = { Text(stringResource(R.string.port)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.52f),
                                )
                            }
                            ChoiceRow(stringResource(R.string.protocol), config.protocol, AppConfig.Protocols) {
                                config = config.copy(protocol = it)
                            }
                            ChoiceRow(stringResource(R.string.field_transport), config.transport, AppConfig.Transports) {
                                config = config.copy(transport = it)
                            }
                            OutlinedTextField(
                                value = config.token,
                                onValueChange = { config = config.copy(token = it) },
                                label = { Text(stringResource(R.string.field_token)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.sni,
                                    onValueChange = { config = config.copy(sni = it) },
                                    label = { Text(stringResource(R.string.field_sni)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.path,
                                    onValueChange = { config = config.copy(path = it) },
                                    label = { Text(stringResource(R.string.field_path)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = config.tunnelSecurity,
                                onValueChange = { config = config.copy(tunnelSecurity = it.lowercase()) },
                                label = { Text(stringResource(R.string.field_security)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = config.flow,
                                onValueChange = { config = config.copy(flow = it) },
                                label = { Text(stringResource(R.string.field_flow)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = config.realityPublicKey,
                                onValueChange = { config = config.copy(realityPublicKey = it) },
                                label = { Text(stringResource(R.string.field_reality_public_key)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.realityFingerprint,
                                    onValueChange = { config = config.copy(realityFingerprint = it) },
                                    label = { Text(stringResource(R.string.field_fingerprint)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.realityShortId,
                                    onValueChange = { config = config.copy(realityShortId = it) },
                                    label = { Text(stringResource(R.string.field_short_id)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = config.realitySpiderX,
                                onValueChange = { config = config.copy(realitySpiderX = it) },
                                label = { Text(stringResource(R.string.field_spider_x)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ChoiceRow(stringResource(R.string.field_upstream), config.upstreamProtocol, AppConfig.UpstreamProtocols) {
                                config = config.copy(upstreamProtocol = it)
                            }
                            ToggleRow(stringResource(R.string.field_tls), config.tls) { config = config.copy(tls = it) }
                            ToggleRow(stringResource(R.string.field_tls_insecure), config.tlsInsecure) {
                                config = config.copy(tlsInsecure = it)
                            }
                            ToggleRow(stringResource(R.string.field_mux), config.mux) { config = config.copy(mux = it) }
                            ToggleRow(stringResource(R.string.field_udp), config.udp) { config = config.copy(udp = it) }
                        }
                }
            }
        }
    }
}

private const val MAX_FULL_CONFIG_LENGTH = 512 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    AppTopBar(
        title = title,
        onBack = onBack,
        actions = {
            FilledTonalButton(
                onClick = onSave,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(title: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(title) },
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    val vpnState by TcptunState.state.collectAsState()
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

private fun shareProfile(context: Context, profile: AppConfig) {
    context.startActivity(
        Intent.createChooser(createProfileShareIntent(profile), context.getString(R.string.share_profile)),
    )
}

internal fun createProfileShareIntent(profile: AppConfig): Intent {
    val uri = requireNotNull(ProfileUriCodec.encode(profile)) { "profile cannot be encoded as a URI" }
    return Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, uri)
}

private data class TcpingTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
)

private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = clipboard.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

private fun clearClipboardText(context: Context, expectedText: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val currentText = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
    if (currentText != expectedText) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

private fun needsNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

private fun startVpn(context: Context, plan: ProfileRunPlan) {
    TcptunState.setStatus("Starting")
    TcptunState.appendLog("start requested")
    runCatching {
        val intent = TcptunVpnService.startIntent(context, plan)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }.onFailure { err ->
        TcptunState.error(err.message ?: context.getString(R.string.start_failed))
    }
}

private fun stopVpn(context: Context) {
    TcptunState.setStatus("Stopping")
    TcptunState.appendLog("stop requested")
    runCatching {
        context.startService(TcptunVpnService.stopIntent(context))
    }.onFailure { err ->
        TcptunState.error(err.message ?: context.getString(R.string.stop_failed))
    }
}

private fun updateVpnOutbounds(context: Context, plan: ProfileRunPlan) {
    TcptunState.appendLog("connection update requested")
    runCatching {
        context.startService(TcptunVpnService.updateOutboundsIntent(context, plan))
    }.onFailure { err ->
        TcptunState.error(err.message ?: context.getString(R.string.start_failed))
    }
}

private fun applyRuntimeSettings(context: Context) {
    val status = TcptunState.status
    if (status != "Starting" && status != "Running") return
    runCatching {
        context.startService(TcptunVpnService.applyRuntimeSettingsIntent(context))
    }.onFailure { err ->
        TcptunState.appendLog("runtime settings apply request failed: ${err.message}")
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

internal fun canStartTcping(status: String, activeProfileCount: Int): Boolean =
    status == "Running" && activeProfileCount > 0

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

private fun bridgeTimestampLabel(timestampMs: Long, noneLabel: String): String {
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
