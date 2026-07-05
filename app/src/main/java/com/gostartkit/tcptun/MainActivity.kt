package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
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
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
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
    val emptyClipboard = stringResource(R.string.empty_clipboard)
    val profileDeletedPrefix = stringResource(R.string.profile_deleted_prefix)
    val undoLabel = stringResource(R.string.undo)
    var state by remember { mutableStateOf(ProfileStore.load(context)) }
    var pendingConfig by remember { mutableStateOf<AppConfig?>(null) }
    var pendingNotificationConfig by remember { mutableStateOf<AppConfig?>(null) }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showRouteEditor by remember { mutableStateOf(false) }
    var showAppFilter by remember { mutableStateOf(false) }
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
        if (link.isBlank()) {
            TcptunState.error(emptyClipboard)
            return
        }
        ProfileUriCodec.decode(link).fold(
            onSuccess = { profile ->
                save(ProfilesState(state.profiles + profile, profile.id))
            },
            onFailure = { err ->
                TcptunState.error(err.message ?: "import failed")
            },
        )
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
    if (showRouteEditor) {
        RouteConfigPage(
            onBack = { showRouteEditor = false },
        )
    } else if (showAppFilter) {
        AppFilterPage(
            onBack = { showAppFilter = false },
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
    } else if (editing == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(title = stringResource(R.string.profiles_title))
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                MainActionsFab(
                    onAdd = { editingProfile = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") },
                    onImport = ::importFromClipboard,
                    onRouteRules = { showRouteEditor = true },
                    onAppFilter = { showAppFilter = true },
                    onDiagnostics = { showDiagnostics = true },
                    onSettings = { showSettings = true },
                    onBatterySettings = { openBatteryOptimizationSettings(context) },
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
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRouteRules: () -> Unit,
    onAppFilter: () -> Unit,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.actions),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = colors.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null)
                },
                text = { Text(stringResource(R.string.import_from_clipboard)) },
                onClick = {
                    expanded = false
                    onImport()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                },
                text = { Text(stringResource(R.string.add_profile)) },
                onClick = {
                    expanded = false
                    onAdd()
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
                text = { Text(stringResource(R.string.route_rules)) },
                onClick = {
                    expanded = false
                    onRouteRules()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.Apps, contentDescription = null)
                },
                text = { Text(stringResource(R.string.app_filter)) },
                onClick = {
                    expanded = false
                    onAppFilter()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.NetworkCheck, contentDescription = null)
                },
                text = { Text(stringResource(R.string.diagnostics)) },
                onClick = {
                    expanded = false
                    onDiagnostics()
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
                    expanded = false
                    onSettings()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Rounded.BatterySaver, contentDescription = null)
                },
                text = { Text(stringResource(R.string.battery_settings)) },
                onClick = {
                    expanded = false
                    onBatterySettings()
                },
                colors = MenuDefaults.itemColors(
                    textColor = colors.onSurface,
                    leadingIconColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: AppConfig,
    selected: Boolean,
    status: String?,
    enabled: Boolean,
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
private fun AppFilterPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TcptunVpnService.readAppFilter(context)) }
    val apps = remember(context, filter) {
        loadFilterableApps(context, filter.excludedApps + filter.includedApps)
    }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filteredApps = remember(apps, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                    it.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
        }
    }

    fun saveFilter(next: AppFilterConfig) {
        TcptunVpnService.writeAppFilter(context, next)
        filter = TcptunVpnService.readAppFilter(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppFilterTopBar(
                onBack = onBack,
                onReset = { saveFilter(AppFilterConfig()) },
                canReset = filter.mode != AppFilterMode.ProxyAll ||
                    filter.excludedApps.isNotEmpty() ||
                    filter.includedApps.isNotEmpty(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                saveFilter(
                                    if (filter.mode == AppFilterMode.ProxyAll) {
                                        AppFilterConfig(mode = AppFilterMode.BypassAll, includedApps = filter.includedApps)
                                    } else {
                                        AppFilterConfig(mode = AppFilterMode.ProxyAll, excludedApps = filter.excludedApps)
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                appFilterModeTitle(filter),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                appFilterModeDescription(filter),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = filter.mode == AppFilterMode.BypassAll,
                            onCheckedChange = { checked ->
                                saveFilter(
                                    if (checked) {
                                        AppFilterConfig(mode = AppFilterMode.BypassAll, includedApps = filter.includedApps)
                                    } else {
                                        AppFilterConfig(mode = AppFilterMode.ProxyAll, excludedApps = filter.excludedApps)
                                    },
                                )
                            },
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                        },
                        label = { Text(stringResource(R.string.search_apps)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    AppFilterListHeader(
                        totalApps = apps.size,
                        selectedApps = selectedAppCount(filter, apps),
                        allSelected = allAppsSelected(filter, apps),
                        onChange = { checked ->
                            saveFilter(
                                if (checked) {
                                    AppFilterConfig()
                                } else {
                                    AppFilterConfig(mode = AppFilterMode.BypassAll)
                                },
                            )
                        },
                    )
                }
                if (filteredApps.isEmpty()) {
                    item {
                        AppFilterEmptyState()
                    }
                }
                items(filteredApps, key = { it.packageName }) { app ->
                    val proxied = isAppProxied(filter, app.packageName)
                    AppFilterRow(
                        app = app,
                        proxied = proxied,
                        onChange = { checked ->
                            val next = if (filter.mode == AppFilterMode.ProxyAll) {
                                filter.copy(
                                    excludedApps = if (checked) {
                                        filter.excludedApps - app.packageName
                                    } else {
                                        filter.excludedApps + app.packageName
                                    },
                                )
                            } else {
                                filter.copy(
                                    includedApps = if (checked) {
                                        filter.includedApps + app.packageName
                                    } else {
                                        filter.includedApps - app.packageName
                                    },
                                )
                            }
                            saveFilter(next)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppFilterTopBar(
    onBack: () -> Unit,
    onReset: () -> Unit,
    canReset: Boolean,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_filter), style = MaterialTheme.typography.titleLarge) },
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
            TextButton(
                enabled = canReset,
                onClick = onReset,
            ) {
                Text(
                    stringResource(R.string.reset),
                    color = if (canReset) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AppFilterListHeader(
    totalApps: Int,
    selectedApps: Int,
    allSelected: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (allSelected) colors.secondaryContainer else colors.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!allSelected) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    stringResource(R.string.all_apps),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    stringResource(R.string.selected_apps_count, selectedApps, totalApps),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    when {
                        selectedApps == totalApps && totalApps > 0 -> stringResource(R.string.all_selected)
                        selectedApps == 0 -> stringResource(R.string.none_selected)
                        else -> stringResource(R.string.partially_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (allSelected) colors.primary else colors.tertiary,
                )
            }
            Switch(
                checked = allSelected,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun AppFilterRow(app: AppEntry, proxied: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (proxied) colors.secondaryContainer else colors.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!proxied) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Apps,
                contentDescription = null,
                tint = if (proxied) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    if (proxied) stringResource(R.string.use_proxy) else stringResource(R.string.bypass_proxy),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (proxied) colors.primary else colors.tertiary,
                )
            }
            Switch(checked = proxied, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun AppFilterEmptyState() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            stringResource(R.string.empty_app_filter),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun RouteConfigPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf(parseRouteRules(TcptunVpnService.readManualRouteConfig(context))) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showAddRule by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val routeTypeLabels = RouteRuleType.entries.map { stringResource(it.labelRes) }
    val routeRulesSaved = stringResource(R.string.route_rules_saved)
    val routeRulesSaveFailed = stringResource(R.string.route_rules_save_failed)
    val routeRulesCleared = stringResource(R.string.route_rules_cleared)
    val routeRulesClearFailed = stringResource(R.string.route_rules_clear_failed)

    fun saveRules(next: List<RouteRule>): Result<Unit> {
        return TcptunVpnService.writeManualRouteConfig(context, buildRouteConfig(next))
            .onSuccess {
                rules = next
                message = routeRulesSaved
                error = ""
            }
            .onFailure { err ->
                error = err.message ?: routeRulesSaveFailed
                message = ""
            }
    }

    val selected = selectedIndex?.let { rules.getOrNull(it) }
    if (selected != null) {
        RouteRuleDetailPage(
            rule = selected,
            index = selectedIndex ?: 0,
            onBack = { selectedIndex = null },
            onSave = { updated ->
                val targetIndex = selectedIndex ?: return@RouteRuleDetailPage Result.failure(
                    IllegalStateException("missing selected route rule"),
                )
                saveRules(rules.toMutableList().also { it[targetIndex] = updated })
            },
            onDelete = {
                val targetIndex = selectedIndex ?: return@RouteRuleDetailPage Result.failure(
                    IllegalStateException("missing selected route rule"),
                )
                saveRules(rules.toMutableList().also { it.removeAt(targetIndex) })
                    .onSuccess { selectedIndex = null }
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RouteListTopBar(
                title = stringResource(R.string.route_rules),
                onBack = onBack,
                onAdd = { showAddRule = true },
                onReset = {
                    TcptunVpnService.resetManualRouteConfig(context).fold(
                        onSuccess = {
                            rules = parseRouteRules(TcptunVpnService.readManualRouteConfig(context))
                            message = routeRulesCleared
                            error = ""
                        },
                        onFailure = { err ->
                            error = err.message ?: routeRulesClearFailed
                            message = ""
                        },
                    )
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
                        stringResource(R.string.route_rules_summary, rules.size, TcptunVpnService.routeConfigFile(context).name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (error.isNotBlank()) {
                        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (rules.isEmpty()) {
                    item {
                        RouteEmptyState(onAdd = { showAddRule = true })
                    }
                }
                itemsIndexed(rules) { index, rule ->
                    RouteRuleRow(
                        rule = rule,
                        typeLabel = routeTypeLabels[rule.type.ordinal],
                        onClick = { selectedIndex = index },
                    )
                }
            }
        }
    }

    if (showAddRule) {
        RouteRuleDialog(
            onDismiss = { showAddRule = false },
            onSave = { rule ->
                saveRules(rules + rule).onSuccess {
                    showAddRule = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteListTopBar(
    title: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
) {
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
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add_rule),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun RouteRuleRow(rule: RouteRule, typeLabel: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(56.dp)
                    .background(colors.tertiary, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(typeLabel, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(rule.value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                Text(
                    "force_upstream.${rule.type.jsonKey}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun RouteEmptyState(onAdd: () -> Unit) {
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
                stringResource(R.string.empty_route_rules),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onAdd) {
                Text(stringResource(R.string.add_rule))
            }
        }
    }
}

@Composable
private fun RouteRuleDetailPage(
    rule: RouteRule,
    index: Int,
    onBack: () -> Unit,
    onSave: (RouteRule) -> Result<Unit>,
    onDelete: () -> Result<Unit>,
) {
    var type by remember(index) { mutableStateOf(rule.type) }
    var value by remember(index) { mutableStateOf(rule.value) }
    var message by remember(index) { mutableStateOf("") }
    var error by remember(index) { mutableStateOf("") }
    val routeTypes = RouteRuleType.entries
    val routeTypeLabels = routeTypes.map { stringResource(it.labelRes) }
    val routeRuleRequired = stringResource(R.string.route_rule_required)
    val savedLabel = stringResource(R.string.saved)
    val saveFailed = stringResource(R.string.save_failed)
    val deleteFailed = stringResource(R.string.delete_failed)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditTopBar(
                title = stringResource(R.string.route_rule_detail),
                onBack = onBack,
                onSave = {
                    val trimmed = value.trim()
                    if (trimmed.isBlank()) {
                        error = routeRuleRequired
                        message = ""
                        return@EditTopBar
                    }
                    onSave(RouteRule(type, trimmed)).fold(
                        onSuccess = {
                            message = savedLabel
                            error = ""
                        },
                        onFailure = { err ->
                            error = err.message ?: saveFailed
                            message = ""
                        },
                    )
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
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                ChoiceRow(stringResource(R.string.rule_type), routeTypeLabels[type.ordinal], routeTypeLabels) { selected ->
                    type = routeTypes[routeTypeLabels.indexOf(selected).coerceAtLeast(0)]
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        message = ""
                        error = ""
                    },
                    label = { Text(stringResource(R.string.rule)) },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        onDelete().fold(
                            onSuccess = {
                                message = ""
                                error = ""
                            },
                            onFailure = { err ->
                                error = err.message ?: deleteFailed
                                message = ""
                            },
                        )
                    },
                ) {
                    Text(stringResource(R.string.delete_this_rule), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RouteRuleDialog(onDismiss: () -> Unit, onSave: (RouteRule) -> Result<Unit>) {
    var type by remember { mutableStateOf(RouteRuleType.IPCIDRs) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val routeTypes = RouteRuleType.entries
    val routeTypeLabels = routeTypes.map { stringResource(it.labelRes) }
    val routeRuleRequired = stringResource(R.string.route_rule_required)
    val saveFailed = stringResource(R.string.save_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_route_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                ChoiceRow(stringResource(R.string.rule_type), routeTypeLabels[type.ordinal], routeTypeLabels) { selected ->
                    type = routeTypes[routeTypeLabels.indexOf(selected).coerceAtLeast(0)]
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = ""
                    },
                    label = { Text(stringResource(R.string.rule)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isBlank()) {
                        error = routeRuleRequired
                        return@Button
                    }
                    onSave(RouteRule(type, trimmed)).onFailure { err ->
                        error = err.message ?: saveFailed
                    }
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
private fun EditProfilePage(initial: AppConfig, onBack: () -> Unit, onSave: (AppConfig) -> Unit) {
    var config by remember(initial.id) { mutableStateOf(initial) }
    var formError by remember(initial.id) { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditTopBar(
                title = if (initial.serverHost.isBlank()) stringResource(R.string.add_profile) else stringResource(R.string.edit_profile),
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

private enum class RouteRuleType(val jsonKey: String, val labelRes: Int) {
    Domains("domains", R.string.route_type_domains),
    DomainRegexes("domain_regexes", R.string.route_type_domain_regexes),
    DomainSuffixes("domain_suffixes", R.string.route_type_domain_suffixes),
    IPs("ips", R.string.route_type_ips),
    IPCIDRs("ip_cidrs", R.string.route_type_ip_cidrs),
    IPRanges("ip_ranges", R.string.route_type_ip_ranges),
}

private data class RouteRule(
    val type: RouteRuleType,
    val value: String,
)

private fun parseRouteRules(routeConfig: String): List<RouteRule> {
    return runCatching {
        val root = JSONObject(routeConfig.ifBlank { "{}" })
        val forceUpstream = root.optJSONObject("force_upstream") ?: JSONObject()
        buildList {
            RouteRuleType.entries.forEach { type ->
                val array = forceUpstream.optJSONArray(type.jsonKey) ?: return@forEach
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) {
                        add(RouteRule(type, value))
                    }
                }
            }
        }
    }.getOrElse { emptyList() }
}

private fun buildRouteConfig(rules: List<RouteRule>): String {
    val forceUpstream = JSONObject()
    RouteRuleType.entries.forEach { type ->
        val array = JSONArray()
        rules.filter { it.type == type }
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .forEach { array.put(it) }
        forceUpstream.put(type.jsonKey, array)
    }
    return JSONObject()
        .put("force_upstream", forceUpstream)
        .toString(2)
}

private data class AppEntry(
    val label: String,
    val packageName: String,
)

private fun selectedAppCount(filter: AppFilterConfig, apps: List<AppEntry>): Int {
    return apps.count { isAppProxied(filter, it.packageName) }
}

private fun allAppsSelected(filter: AppFilterConfig, apps: List<AppEntry>): Boolean {
    return apps.isNotEmpty() && apps.all { isAppProxied(filter, it.packageName) }
}

private fun isAppProxied(filter: AppFilterConfig, packageName: String): Boolean {
    return when (filter.mode) {
        AppFilterMode.ProxyAll -> packageName !in filter.excludedApps
        AppFilterMode.BypassAll -> packageName in filter.includedApps
    }
}

@Composable
private fun appFilterModeTitle(filter: AppFilterConfig): String {
    return when (filter.mode) {
        AppFilterMode.ProxyAll -> stringResource(R.string.app_filter_proxy_all_title)
        AppFilterMode.BypassAll -> stringResource(R.string.app_filter_bypass_all_title)
    }
}

@Composable
private fun appFilterModeDescription(filter: AppFilterConfig): String {
    return when (filter.mode) {
        AppFilterMode.ProxyAll -> stringResource(R.string.app_filter_proxy_all_description)
        AppFilterMode.BypassAll -> stringResource(R.string.app_filter_bypass_all_description)
    }
}

private fun loadFilterableApps(context: Context, savedPackages: Set<String>): List<AppEntry> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }
    val appsByPackage = linkedMapOf<String, AppEntry>()
    resolveInfos.forEach { info ->
        val packageName = info.activityInfo?.packageName?.trim().orEmpty()
        if (packageName.isBlank() || packageName == context.packageName) return@forEach
        val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            .ifBlank { packageName }
        appsByPackage.putIfAbsent(packageName, AppEntry(label, packageName))
    }
    savedPackages
        .filter { it.isNotBlank() && it != context.packageName }
        .forEach { packageName ->
            appsByPackage.putIfAbsent(packageName, AppEntry(packageName, packageName))
        }
    return appsByPackage.values.sortedWith(
        compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.packageName },
    )
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
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, profile.shareText())
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_profile)))
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

private fun openBatteryOptimizationSettings(context: Context) {
    val packageName = context.packageName
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
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
    TcpingTarget("GitHub", "github.com"),
    TcpingTarget("Cloudflare", "cloudflare.com"),
)
