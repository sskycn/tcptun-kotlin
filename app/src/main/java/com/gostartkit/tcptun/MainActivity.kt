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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tcptun.client.ui.theme.TcpTunTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SnackbarAutoDismissMillis = 6_000L

private data class LocalIpInfo(
    val underlyingInterface: String = "",
    val underlyingIpv4: String = "",
    val underlyingGatewayIpv4: String = "",
    val underlyingIpv6: String = "",
    val vpnIpv4: String = "",
    val vpnIpv6: String = "",
)

private data class NetworkLinkInfo(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties,
)

private fun readLocalIpInfo(connectivity: ConnectivityManager, networks: Collection<Network>): LocalIpInfo {
    val links = networks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        val linkProperties = connectivity.getLinkProperties(network) ?: return@mapNotNull null
        NetworkLinkInfo(network, capabilities, linkProperties)
    }
    val underlying = links
        .filter {
            it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        .maxByOrNull {
            underlyingNetworkScore(
                validated = it.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ethernet = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                wifi = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }
    val activeNetwork = connectivity.activeNetwork
    val vpn = links.firstOrNull {
        it.network == activeNetwork && it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } ?: links.firstOrNull { it.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    return LocalIpInfo(
        underlyingInterface = underlying?.linkProperties?.interfaceName.orEmpty(),
        underlyingIpv4 = formatIpAddresses(underlying?.linkProperties, ipv6 = false),
        underlyingGatewayIpv4 = formatDefaultGatewayIpv4(underlying?.linkProperties),
        underlyingIpv6 = formatIpAddresses(underlying?.linkProperties, ipv6 = true),
        vpnIpv4 = formatIpAddresses(vpn?.linkProperties, ipv6 = false),
        vpnIpv6 = formatIpAddresses(vpn?.linkProperties, ipv6 = true),
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

@Composable
private fun rememberLocalIpInfo(context: Context): LocalIpInfo {
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val initialNetworks = listOfNotNull(connectivity.activeNetwork)
    val info by produceState(
        initialValue = readLocalIpInfo(connectivity, initialNetworks),
        connectivity,
    ) {
        val observedNetworks = initialNetworks.toMutableSet()
        val observedNetworksLock = Any()
        fun refresh(network: Network, available: Boolean) {
            val snapshot = synchronized(observedNetworksLock) {
                if (available) observedNetworks.add(network) else observedNetworks.remove(network)
                observedNetworks.toList()
            }
            launch { value = readLocalIpInfo(connectivity, snapshot) }
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
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val registered = runCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess
        awaitDispose {
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
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
            TcpTunTheme(dynamicColor = true) {
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
    val profileDeletedPrefix = stringResource(R.string.profile_deleted_prefix)
    val undoLabel = stringResource(R.string.undo)
    var state by remember { mutableStateOf(ProfileStore.load(context)) }
    var pendingDeepLinkProfile by remember { mutableStateOf<AppConfig?>(null) }
    var pendingConfig by remember { mutableStateOf<ProfileRunPlan?>(null) }
    var pendingNotificationConfig by remember { mutableStateOf<ProfileRunPlan?>(null) }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRouteManagement by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var profileQrCode by remember { mutableStateOf<AppConfig?>(null) }
    var tcpingMessage by remember { mutableStateOf("") }
    var tcpingInProgress by remember { mutableStateOf(false) }
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
        tcpingMessage = ""
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
        val nextActiveIds = if (profile.id in state.activeIds) {
            state.activeIds - profile.id
        } else {
            state.activeIds + profile.id
        }
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
                message = "$profileDeletedPrefix ${profile.name}",
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
                val serverConnected = hasServerConnection(vpnState.diagnostics)
                BottomStatus(
                    status = vpnState.status,
                    error = vpnState.lastError,
                    tcpingMessage = tcpingMessage,
                    tcpingInProgress = tcpingInProgress,
                    hasProfile = state.profiles.isNotEmpty(),
                    tcpingEnabled = serverConnected,
                    onClick = {
                        if (isVpnTransitionStatus(vpnState.status)) return@BottomStatus
                        if (state.profiles.isEmpty()) return@BottomStatus
                        if (tcpingInProgress) return@BottomStatus
                        if (!serverConnected) return@BottomStatus
                        val tcpingTarget = TCPING_TARGETS[tcpingTargetIndex]
                        val tcpingSettings = TcptunVpnService.readRuntimeSettings(context)
                        tcpingTargetIndex = (tcpingTargetIndex + 1) % TCPING_TARGETS.size
                        tcpingInProgress = true
                        tcpingMessage = ""
                        screenScope.launch {
                            val result = tcping(context, tcpingTarget, tcpingSettings)
                            tcpingMessage = result.message
                            if (!result.success) {
                                TcptunVpnService.requestDenseHealthCheck("tcping failed: ${tcpingTarget.label}")
                            }
                            tcpingInProgress = false
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (listIpInfo.underlyingIpv4.isNotBlank() || listIpInfo.underlyingGatewayIpv4.isNotBlank()) {
                        item(key = "underlying-ipv4-header") {
                            ProfileListHeader(
                                localIpv4 = listIpInfo.underlyingIpv4.substringBefore('/').substringBefore('\n'),
                                gatewayIpv4 = listIpInfo.underlyingGatewayIpv4.substringBefore('\n'),
                            )
                        }
                    }
                    items(state.profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            running = profile.id in state.activeIds && isVpnActiveStatus(vpnState.status),
                            status = vpnState.status.takeIf { profile.id in state.activeIds },
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
    localIpv4: String,
    gatewayIpv4: String,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = localIpv4,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gatewayIpv4,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AutoDismissSnackbarHost(hostState: SnackbarHostState) {
    val snackbarData = hostState.currentSnackbarData
    LaunchedEffect(snackbarData) {
        if (snackbarData != null) {
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
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
                    shape = RoundedCornerShape(12.dp),
                    containerColor = colors.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
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
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
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
    status: String?,
    enabled: Boolean,
    shareable: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onShowQrCode: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rowColor = if (running) colors.secondaryContainer else colors.surfaceContainerLow
    val statusColor = if (running) colors.primary else colors.onSurfaceVariant
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
                    .background(colors.errorContainer, RoundedCornerShape(8.dp))
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 108.dp),
            shape = RoundedCornerShape(8.dp),
            color = rowColor,
            tonalElevation = if (running) 2.dp else 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(72.dp)
                        .background(if (running) statusColor else Color.Transparent, RoundedCornerShape(8.dp)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                    )
                    Text(
                        profile.maskedAddress(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = status?.let { "${profile.label()} · ${vpnStatusLabel(it)}" } ?: profile.label(),
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor,
                    )
                    Text(
                        text = stringResource(if (running) R.string.connection_running else R.string.connection_stopped),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onShare,
                    enabled = shareable,
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.share),
                        tint = if (shareable) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
                    )
                }
                IconButton(
                    onClick = onShowQrCode,
                    enabled = shareable,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(
                        Icons.Rounded.QrCode2,
                        contentDescription = stringResource(R.string.show_qr_code),
                        tint = if (shareable) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
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
                    shape = RoundedCornerShape(12.dp),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.empty_profiles),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onAdd) {
                Text(stringResource(R.string.add_profile))
            }
        }
    }
}

@Composable
private fun BottomStatus(
    status: String,
    error: String,
    tcpingMessage: String,
    tcpingInProgress: Boolean,
    hasProfile: Boolean,
    tcpingEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val text = when {
        error.isNotBlank() -> stringResource(R.string.error_prefix, error)
        tcpingInProgress -> stringResource(R.string.tcping_running)
        tcpingMessage.isNotBlank() -> tcpingMessage
        status == "Running" && !tcpingEnabled -> stringResource(R.string.connected_waiting_server)
        status == "Running" -> stringResource(R.string.connected_tap_test)
        status == "Starting" -> stringResource(R.string.connecting)
        status == "Stopping" -> stringResource(R.string.stopping)
        hasProfile -> stringResource(R.string.not_connected_tap_profile_or_test)
        else -> stringResource(R.string.not_connected_add_profile)
    }
    val contentColor = when {
        error.isNotBlank() -> colors.error
        tcpingMessage.startsWith(stringResource(R.string.tcping_success_prefix)) || status == "Running" -> colors.primary
        status == "Starting" || status == "Stopping" || tcpingInProgress -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    BottomAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(enabled = hasProfile && tcpingEnabled && !tcpingInProgress && !isVpnTransitionStatus(status), onClick = onClick),
        containerColor = colors.surfaceContainer,
        contentColor = contentColor,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.runtime_diagnostics),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
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
private fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val ipInfo = rememberLocalIpInfo(context)
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.transparent_proxy_settings),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.current_effective),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
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
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val noneLabel = stringResource(R.string.none)
                        Text(
                            stringResource(R.string.ip_information),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DiagnosticsLine(
                            stringResource(R.string.ip_underlying_interface),
                            ipInfo.underlyingInterface.ifBlank { noneLabel },
                        )
                        DiagnosticsLine(
                            stringResource(R.string.ip_underlying_ipv4),
                            ipInfo.underlyingIpv4.ifBlank { noneLabel },
                        )
                        DiagnosticsLine(
                            stringResource(R.string.ip_underlying_ipv6),
                            ipInfo.underlyingIpv6.ifBlank { noneLabel },
                        )
                        DiagnosticsLine(
                            stringResource(R.string.ip_vpn_ipv4),
                            ipInfo.vpnIpv4.ifBlank { noneLabel },
                        )
                        DiagnosticsLine(
                            stringResource(R.string.ip_vpn_ipv6),
                            ipInfo.vpnIpv6.ifBlank { noneLabel },
                        )
                        Text(
                            stringResource(R.string.ip_information_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            TopAppBar(
                title = { Text(stringResource(R.string.route_management)) },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.route_rules_count, rules.count { it.enabled }),
                            style = MaterialTheme.typography.titleMedium,
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
                    .background(colors.errorContainer, RoundedCornerShape(8.dp))
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
            shape = RoundedCornerShape(8.dp),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 36.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.empty_route_rules), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onAdd) {
                Text(stringResource(R.string.add_route_rule))
            }
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
    TopAppBar(
        title = { Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            TextButton(onClick = onShowLogs) {
                Text(stringResource(R.string.logs))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun DiagnosticsLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.46f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.54f),
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
                    Text(formError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
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
                ChoiceRow(stringResource(R.string.protocol), config.protocol, AppConfig.Protocols) { config = config.copy(protocol = it) }
                ChoiceRow(stringResource(R.string.field_transport), config.transport, AppConfig.Transports) { config = config.copy(transport = it) }
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
                ToggleRow(stringResource(R.string.field_tls_insecure), config.tlsInsecure) { config = config.copy(tlsInsecure = it) }
                ToggleRow(stringResource(R.string.field_mux), config.mux) { config = config.copy(mux = it) }
                ToggleRow(stringResource(R.string.field_udp), config.udp) { config = config.copy(udp = it) }
                }
            }
        }
    }
}

private const val MAX_FULL_CONFIG_LENGTH = 512 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            Button(onClick = onSave) {
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
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
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
                shape = RoundedCornerShape(8.dp),
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

private data class TcpingResult(
    val target: TcpingTarget,
    val elapsedMs: Long?,
    val error: String?,
)

private data class TcpingCheck(
    val message: String,
    val success: Boolean,
)

private suspend fun tcping(context: Context, target: TcpingTarget, settings: RuntimeSettings): TcpingCheck = withContext(Dispatchers.IO) {
    val result = tcpingTarget(target, settings)
    val elapsedMs = result.elapsedMs
    if (elapsedMs != null) {
        TcpingCheck(context.getString(R.string.tcping_success, target.label, elapsedMs), true)
    } else {
        TcpingCheck(
            context.getString(R.string.tcping_failed, target.label, result.error ?: context.getString(R.string.tcping_failed_fallback)),
            false,
        )
    }
}

private fun tcpingTarget(target: TcpingTarget, settings: RuntimeSettings): TcpingResult {
    val start = System.nanoTime()
    return runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(TcptunVpnService.LOCAL_SOCKS_HOST, settings.socksPort),
                TCPING_TIMEOUT_MS,
            )
            socket.soTimeout = TCPING_TIMEOUT_MS
            socks5Connect(socket, target.host, target.port, settings.socksUsername, settings.socksPassword)
            completeTlsHandshake(socket, target.host, target.port, TCPING_TIMEOUT_MS)
        }
    }.fold(
        onSuccess = {
            TcpingResult(target, (System.nanoTime() - start) / 1_000_000, null)
        },
        onFailure = { err ->
            TcpingResult(target, null, err.message ?: err.javaClass.simpleName)
        },
    )
}

private fun socks5Connect(socket: Socket, host: String, port: Int, username: String, password: String) {
    val input = socket.getInputStream()
    val output = socket.getOutputStream()
    val authEnabled = username.isNotEmpty() || password.isNotEmpty()
    output.write(if (authEnabled) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
    output.flush()
    val methodReply = input.readExact(2)
    require(methodReply[0] == 0x05.toByte()) { "invalid SOCKS5 method reply" }
    when (methodReply[1].toInt() and 0xff) {
        0x00 -> Unit
        0x02 -> socks5Authenticate(input, output, username, password)
        else -> error("SOCKS5 method rejected")
    }

    val hostBytes = host.encodeToByteArray()
    require(hostBytes.size <= 255) { "host is too long" }
    val request = ByteArray(7 + hostBytes.size)
    request[0] = 0x05
    request[1] = 0x01
    request[2] = 0x00
    request[3] = 0x03
    request[4] = hostBytes.size.toByte()
    hostBytes.copyInto(request, destinationOffset = 5)
    request[request.lastIndex - 1] = ((port ushr 8) and 0xff).toByte()
    request[request.lastIndex] = (port and 0xff).toByte()
    output.write(request)
    output.flush()

    val replyHead = input.readExact(4)
    require(replyHead[0] == 0x05.toByte()) { "invalid SOCKS5 reply" }
    require(replyHead[1] == 0x00.toByte()) { "SOCKS5 connect failed: ${replyHead[1].toInt() and 0xff}" }
    val addressLength = when (replyHead[3].toInt() and 0xff) {
        0x01 -> 4
        0x03 -> input.read()
        0x04 -> 16
        else -> error("invalid SOCKS5 address type")
    }
    require(addressLength >= 0) { "SOCKS5 reply ended early" }
    input.readExact(addressLength + 2)
}

private fun socks5Authenticate(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    username: String,
    password: String,
) {
    val usernameBytes = username.encodeToByteArray()
    val passwordBytes = password.encodeToByteArray()
    require(usernameBytes.size <= 255) { "SOCKS5 username is too long" }
    require(passwordBytes.size <= 255) { "SOCKS5 password is too long" }
    val request = ByteArray(3 + usernameBytes.size + passwordBytes.size)
    request[0] = 0x01
    request[1] = usernameBytes.size.toByte()
    usernameBytes.copyInto(request, destinationOffset = 2)
    request[2 + usernameBytes.size] = passwordBytes.size.toByte()
    passwordBytes.copyInto(request, destinationOffset = 3 + usernameBytes.size)
    output.write(request)
    output.flush()
    val reply = input.readExact(2)
    require(reply[0] == 0x01.toByte() && reply[1] == 0x00.toByte()) {
        "SOCKS5 username/password auth failed"
    }
}

private fun java.io.InputStream.readExact(length: Int): ByteArray {
    val data = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(data, offset, length - offset)
        if (read < 0) {
            error("connection closed")
        }
        offset += read
    }
    return data
}

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

internal fun hasServerConnection(diagnostics: TcptunDiagnostics): Boolean {
    if (diagnostics.vpnStatus != "Running") return false
    val state = diagnostics.bridgeEventState.lowercase()
    val phase = diagnostics.bridgeEventPhase.lowercase()
    return state in SERVER_CONNECTED_STATES || phase in SERVER_CONNECTED_PHASES
}

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

private const val TCPING_TIMEOUT_MS = 3_000
private val SERVER_CONNECTED_STATES = setOf("core_ready", "running", "upstream_connected")
private val SERVER_CONNECTED_PHASES = setOf("connected", "upstream_connected")
private val TCPING_TARGETS = listOf(
    TcpingTarget("Google", "google.com"),
    TcpingTarget("GitHub", "github.com"),
    TcpingTarget("Cloudflare", "cloudflare.com"),
)
