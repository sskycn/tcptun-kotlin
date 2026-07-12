package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tcptun.client.ui.theme.TcpTunTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TcpTun)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TcpTunTheme(dynamicColor = true) {
                TcptunScreen()
            }
        }
    }
}

@Composable
fun TcptunScreen() {
    val context = LocalContext.current
    val invalidQrCode = stringResource(R.string.invalid_qr_code)
    val qrScannerFailed = stringResource(R.string.qr_scanner_failed)
    val profileDeletedPrefix = stringResource(R.string.profile_deleted_prefix)
    val undoLabel = stringResource(R.string.undo)
    var state by remember { mutableStateOf(ProfileStore.load(context)) }
    var pendingConfig by remember { mutableStateOf<AppConfig?>(null) }
    var pendingNotificationConfig by remember { mutableStateOf<AppConfig?>(null) }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var tcpingMessage by remember { mutableStateOf("") }
    var tcpingInProgress by remember { mutableStateOf(false) }
    var tcpingTargetIndex by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingConfig?.let {
            startVpn(context, it)
            pendingConfig = null
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingNotificationConfig?.let { config ->
            pendingNotificationConfig = null
            if (!granted) {
                TcptunState.appendLog("notification permission denied; foreground notification may be hidden")
            }
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                pendingConfig = config
                vpnLauncher.launch(prepare)
            } else {
                startVpn(context, config)
            }
        }
    }

    fun save(next: ProfilesState) {
        ProfileStore.save(context, next)
        state = ProfileStore.load(context)
    }

    fun importFromClipboard() {
        val link = clipboardText(context).trim()
        val clipboardProfile = link.takeIf { it.isNotBlank() }
            ?.let(ProfileUriCodec::decode)
            ?.getOrNull()
        if (clipboardProfile != null) {
            save(ProfilesState(state.profiles + clipboardProfile, clipboardProfile.id))
            return
        }

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue?.trim().orEmpty()
                ProfileUriCodec.decode(value).fold(
                    onSuccess = { profile ->
                        save(ProfilesState(state.profiles + profile, profile.id))
                    },
                    onFailure = { TcptunState.error(invalidQrCode) },
                )
            }
            .addOnFailureListener { err ->
                TcptunState.error(err.message?.takeIf { it.isNotBlank() } ?: qrScannerFailed)
            }
    }

    fun startProfile(config: AppConfig) {
        val error = config.validate()
        if (error != null) {
            TcptunState.error(error)
            showLogs = true
            return
        }
        tcpingMessage = ""
        save(state.copy(selectedId = config.id))
        TcptunState.clearLogs()
        if (needsNotificationPermission(context)) {
            pendingNotificationConfig = config
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            pendingConfig = config
            vpnLauncher.launch(prepare)
        } else {
            startVpn(context, config)
        }
    }

    fun toggleProfile(profile: AppConfig) {
        val status = TcptunState.status.value
        val active = isVpnActiveStatus(status)
        if (isVpnTransitionStatus(status)) return
        if (active && profile.id == state.selected?.id) {
            stopVpn(context)
            return
        }
        startProfile(profile)
    }

    fun deleteProfile(profile: AppConfig) {
        val profileIndex = state.profiles.indexOfFirst { it.id == profile.id }
        if (profileIndex < 0) return
        val wasSelected = state.selectedId == profile.id
        val remaining = state.profiles.toMutableList().also { it.removeAt(profileIndex) }
        val nextSelectedId = if (wasSelected) remaining.firstOrNull()?.id else state.selectedId
        save(ProfilesState(remaining, nextSelectedId))
        screenScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "$profileDeletedPrefix ${profile.name}",
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val current = ProfileStore.load(context)
                if (current.profiles.none { it.id == profile.id }) {
                    val restored = current.profiles.toMutableList()
                    restored.add(profileIndex.coerceAtMost(restored.size), profile)
                    save(ProfilesState(restored, if (wasSelected) profile.id else current.selectedId))
                }
            }
        }
    }

    val editing = editingProfile
    if (showDiagnostics) {
        DiagnosticsPage(
            onBack = { showDiagnostics = false },
            onShowLogs = { showLogs = true },
        )
    } else if (showSettings) {
        SettingsPage(
            onBack = { showSettings = false },
        )
    } else if (editing == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(title = stringResource(R.string.profiles_title))
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                MainActionsFab(
                    onImport = ::importFromClipboard,
                )
            },
            bottomBar = {
                val serverConnected = hasServerConnection(TcptunState.diagnostics.value)
                BottomStatus(
                    status = TcptunState.status.value,
                    error = TcptunState.lastError.value,
                    tcpingMessage = tcpingMessage,
                    tcpingInProgress = tcpingInProgress,
                    hasProfile = state.selected != null,
                    tcpingEnabled = serverConnected,
                    onClick = {
                        if (isVpnTransitionStatus(TcptunState.status.value)) return@BottomStatus
                        if (state.selected == null) return@BottomStatus
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == state.selected?.id,
                        status = TcptunState.status.value.takeIf {
                            profile.id == state.selected?.id && isVpnActiveStatus(it)
                        },
                        enabled = !isVpnTransitionStatus(TcptunState.status.value),
                        onClick = { toggleProfile(profile) },
                        shareable = ProfileUriCodec.encode(profile) != null,
                        onShare = { shareProfile(context, profile) },
                        onEdit = { editingProfile = profile },
                        onDelete = { deleteProfile(profile) },
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
                save(ProfilesState(profiles, updated.id))
                editingProfile = null
            },
        )
    }

    if (showLogs) {
        LogsDialog(onDismiss = { showLogs = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
    )
}

@Composable
private fun MainActionsFab(
    onImport: () -> Unit,
) {
    FloatingActionButton(onClick = onImport) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = stringResource(R.string.actions),
        )
    }
}

@Composable
private fun ProfileRow(
    profile: AppConfig,
    selected: Boolean,
    status: String?,
    enabled: Boolean,
    shareable: Boolean,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val rowColor = if (selected) colors.secondaryContainer else colors.surfaceContainerLow
    val statusColor = if (status == "Running") colors.primary else colors.tertiary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp),
        shape = RoundedCornerShape(8.dp),
        color = rowColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
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
                    .background(if (selected) statusColor else Color.Transparent, RoundedCornerShape(8.dp)),
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
            }
            Box(
                modifier = Modifier.padding(end = 8.dp),
            ) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.profile_actions),
                        tint = colors.onSurfaceVariant,
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
                    if (shareable) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.Share, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.share)) },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = colors.onSurface,
                                leadingIconColor = colors.onSurfaceVariant,
                            ),
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Delete, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.error,
                            leadingIconColor = colors.error,
                        ),
                    )
                }
            }
        }
    }
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
    val diagnostics = TcptunState.diagnostics.value
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
    var settings by remember { mutableStateOf(TcptunVpnService.readRuntimeSettings(context)) }
    var socksPortText by remember { mutableStateOf(settings.socksPort.toString()) }
    var settingsDirty by remember { mutableStateOf(false) }
    val diagnostics = TcptunState.diagnostics.value
    val mtuOptions = listOf("1280", "1360", "1400", "1500")

    fun saveSettings(next: RuntimeSettings) {
        val before = settings
        TcptunVpnService.writeRuntimeSettings(context, next)
        settings = TcptunVpnService.readRuntimeSettings(context)
        socksPortText = settings.socksPort.toString()
        if (settings != before) {
            settingsDirty = true
        }
    }

    fun leaveSettings() {
        val socksPort = socksPortText.toIntOrNull()
        if (socksPort == null || socksPort !in 1..65535) return
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
                        DiagnosticsLine(stringResource(R.string.socks_auth), if (settings.socksUsername.isNotEmpty() || settings.socksPassword.isNotEmpty()) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.field_udp), if (diagnostics.udpEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(stringResource(R.string.diag_power_saving), if (diagnostics.powerSavingMode) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                    }
                }
            }
        }
    }
}

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
                            TcptunState.logs.joinToString("\n").ifBlank { noLogs },
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

private fun needsNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

private fun startVpn(context: Context, config: AppConfig) {
    TcptunState.setStatus("Starting")
    TcptunState.appendLog("start requested")
    runCatching {
        val intent = TcptunVpnService.startIntent(context, config)
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

private fun applyRuntimeSettings(context: Context) {
    val status = TcptunState.status.value
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

private fun hasServerConnection(diagnostics: TcptunDiagnostics): Boolean {
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
private val SERVER_CONNECTED_STATES = setOf("running", "upstream_connected")
private val SERVER_CONNECTED_PHASES = setOf("connected", "upstream_connected")
private val TCPING_TARGETS = listOf(
    TcpingTarget("Google", "google.com"),
    TcpingTarget("GitHub", "github.com"),
    TcpingTarget("Cloudflare", "cloudflare.com"),
)
