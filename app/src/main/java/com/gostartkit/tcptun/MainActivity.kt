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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var state by remember { mutableStateOf(ProfileStore.load(context)) }
    var pendingConfig by remember { mutableStateOf<AppConfig?>(null) }
    var pendingNotificationConfig by remember { mutableStateOf<AppConfig?>(null) }
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showRouteEditor by remember { mutableStateOf(false) }
    var showAppFilter by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
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
            TcptunState.error("剪切板为空")
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
                message = "已删除 ${profile.name}",
                actionLabel = "撤销",
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
    } else if (editing == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(title = "配置项")
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                MainActionsFab(
                    onAdd = { editingProfile = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") },
                    onImport = ::importFromClipboard,
                    onRouteRules = { showRouteEditor = true },
                    onAppFilter = { showAppFilter = true },
                    onDiagnostics = { showDiagnostics = true },
                    onBatterySettings = { openBatteryOptimizationSettings(context) },
                )
            },
            bottomBar = {
                BottomStatus(
                    status = TcptunState.status.value,
                    error = TcptunState.lastError.value,
                    tcpingMessage = tcpingMessage,
                    tcpingInProgress = tcpingInProgress,
                    hasProfile = state.selected != null,
                    onClick = {
                        if (isVpnTransitionStatus(TcptunState.status.value)) return@BottomStatus
                        if (state.selected == null) return@BottomStatus
                        if (tcpingInProgress) return@BottomStatus
                        val tcpingTarget = TCPING_TARGETS[tcpingTargetIndex]
                        tcpingTargetIndex = (tcpingTargetIndex + 1) % TCPING_TARGETS.size
                        tcpingInProgress = true
                        tcpingMessage = ""
                        screenScope.launch {
                            tcpingMessage = tcping(tcpingTarget)
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
    onBatterySettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "操作",
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
                text = { Text("从剪切板导入") },
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
                text = { Text("添加配置") },
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
                text = { Text("强制代理规则") },
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
                text = { Text("应用过滤") },
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
                text = { Text("诊断与设置") },
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
                    Icon(Icons.Rounded.BatterySaver, contentDescription = null)
                },
                text = { Text("省电设置") },
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
                        contentDescription = "配置操作",
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
                        text = { Text("分享") },
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
                        text = { Text("编辑") },
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
                        text = { Text("删除") },
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
                "还没有配置项",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onAdd) {
                Text("添加配置")
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
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val text = when {
        error.isNotBlank() -> "错误：$error"
        tcpingInProgress -> "正在 TCPing..."
        tcpingMessage.isNotBlank() -> tcpingMessage
        status == "Running" -> "已连接，点击测试连接"
        status == "Starting" -> "正在连接..."
        status == "Stopping" -> "正在停止..."
        hasProfile -> "未连接，点击列表项启动；点击这里测试连接"
        else -> "未连接，添加配置后点击列表项启动"
    }
    val contentColor = when {
        error.isNotBlank() -> colors.error
        tcpingMessage.startsWith("TCPing 成功") || status == "Running" -> colors.primary
        status == "Starting" || status == "Stopping" || tcpingInProgress -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    BottomAppBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(enabled = hasProfile && !tcpingInProgress && !isVpnTransitionStatus(status), onClick = onClick),
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
    val context = LocalContext.current
    var settings by remember { mutableStateOf(TcptunVpnService.readRuntimeSettings(context)) }
    val diagnostics = TcptunState.diagnostics.value
    val mtuOptions = listOf("1280", "1360", "1400", "1500")

    fun saveSettings(next: RuntimeSettings) {
        TcptunVpnService.writeRuntimeSettings(context, next)
        settings = TcptunVpnService.readRuntimeSettings(context)
        TcptunState.appendLog("runtime settings saved: mtu=${settings.mtu} udp=${settings.udpEnabled}")
    }

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
                            "运行诊断",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DiagnosticsLine("VPN", diagnostics.vpnStatus)
                        DiagnosticsLine("Underlying network", diagnostics.underlyingNetwork)
                        DiagnosticsLine("Bridge", diagnostics.bridgeStatus)
                        DiagnosticsLine("Go state", diagnostics.bridgeEventState)
                        DiagnosticsLine("Go phase", diagnostics.bridgeEventPhase)
                        DiagnosticsLine("Go listen", diagnostics.bridgeListen.ifBlank { "None" })
                        DiagnosticsLine("Go remote", diagnostics.bridgeRemote.ifBlank { "None" })
                        DiagnosticsLine("Go active", diagnostics.bridgeActiveConnections.toString())
                        DiagnosticsLine("Go error", diagnostics.bridgeLastError.ifBlank { "None" })
                        DiagnosticsLine("Go event time", bridgeTimestampLabel(diagnostics.bridgeTimestampMs))
                        DiagnosticsLine("127.0.0.1:1080", if (diagnostics.localProxyReachable) "Reachable" else "Not reachable")
                        DiagnosticsLine("Socket protect", if (diagnostics.socketProtectEnabled) "Enabled" else "Disabled")
                        DiagnosticsLine("最近重启原因", diagnostics.lastRestartReason)
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
                                "透明代理参数",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        ChoiceRow("MTU", settings.mtu.toString(), mtuOptions) { value ->
                            saveSettings(settings.copy(mtu = value.toIntOrNull() ?: TcptunVpnService.DEFAULT_VPN_MTU))
                        }
                        ToggleRow("启用 UDP 转发", settings.udpEnabled) { checked ->
                            saveSettings(settings.copy(udpEnabled = checked))
                        }
                        Text(
                            "设置会在下次启动 VPN 时生效。关闭 UDP 可用于判断断流是否和 QUIC/UDP 路径有关。",
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
                            "当前生效",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DiagnosticsLine("MTU", diagnostics.mtu.toString())
                        DiagnosticsLine("UDP", if (diagnostics.udpEnabled) "Enabled" else "Disabled")
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
        title = { Text("诊断与设置", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            TextButton(onClick = onShowLogs) {
                Text("日志")
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
                        label = { Text("搜索应用") },
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
        title = { Text("应用过滤", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
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
                    "重置",
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
                    "全部应用",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    "已选择 $selectedApps / $totalApps 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    when {
                        selectedApps == totalApps && totalApps > 0 -> "已全选"
                        selectedApps == 0 -> "已全取消"
                        else -> "已选择部分"
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
                    if (proxied) "走代理" else "不走代理",
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
            "没有匹配的应用",
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

    fun saveRules(next: List<RouteRule>): Result<Unit> {
        return TcptunVpnService.writeManualRouteConfig(context, buildRouteConfig(next))
            .onSuccess {
                rules = next
                message = "已保存，强制代理规则已生效"
                error = ""
            }
            .onFailure { err ->
                error = err.message ?: "路由配置保存失败"
                message = ""
            }
    }

    val selected = selectedIndex?.let { rules.getOrNull(it) }
    if (selected != null) {
        RouteRuleDetailPage(
            rule = selected,
            index = selectedIndex ?: 0,
            rules = rules,
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
                title = "强制代理规则",
                onBack = onBack,
                onAdd = { showAddRule = true },
                onReset = {
                    TcptunVpnService.resetManualRouteConfig(context).fold(
                        onSuccess = {
                            rules = parseRouteRules(TcptunVpnService.readManualRouteConfig(context))
                            message = "已清空强制代理规则"
                            error = ""
                        },
                        onFailure = { err ->
                            error = err.message ?: "清空强制代理规则失败"
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
                        "强制代理规则 ${rules.size} 条 · 启动时写入 ${TcptunVpnService.routeConfigFile(context).name}",
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
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            TextButton(onClick = onReset) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "添加规则",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun RouteRuleRow(rule: RouteRule, onClick: () -> Unit) {
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
                Text(rule.type.label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
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
                "还没有强制代理规则",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onAdd) {
                Text("添加规则")
            }
        }
    }
}

@Composable
private fun RouteRuleDetailPage(
    rule: RouteRule,
    index: Int,
    rules: List<RouteRule>,
    onBack: () -> Unit,
    onSave: (RouteRule) -> Result<Unit>,
    onDelete: () -> Result<Unit>,
) {
    var type by remember(index) { mutableStateOf(rule.type) }
    var value by remember(index) { mutableStateOf(rule.value) }
    var message by remember(index) { mutableStateOf("") }
    var error by remember(index) { mutableStateOf("") }
    val stats = routeRuleStats(rule, rules, index)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditTopBar(
                title = "强制代理详情",
                onBack = onBack,
                onSave = {
                    val trimmed = value.trim()
                    if (trimmed.isBlank()) {
                        error = "规则不能为空"
                        message = ""
                        return@EditTopBar
                    }
                    onSave(RouteRule(type, trimmed)).fold(
                        onSuccess = {
                            message = "已保存"
                            error = ""
                        },
                        onFailure = { err ->
                            error = err.message ?: "保存失败"
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
                RouteStatsBlock(stats)
                ChoiceRow("规则类型", type.label, RouteRuleType.entries.map { it.label }) { selected ->
                    type = RouteRuleType.entries.first { it.label == selected }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        message = ""
                        error = ""
                    },
                    label = { Text("规则") },
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
                                error = err.message ?: "删除失败"
                                message = ""
                            },
                        )
                    },
                ) {
                    Text("删除这条规则", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RouteStatsBlock(stats: RouteRuleStats) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("统计数据", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            StatLine("位置", "#${stats.position}")
            StatLine("类型", stats.typeLabel)
            StatLine("强制代理总数", stats.totalRules.toString())
            StatLine("有效规则", stats.effectiveRules.toString())
            StatLine("同类规则", stats.sameTypeRules.toString())
            StatLine("重复规则", stats.duplicateRules.toString())
            StatLine("与内置规则重复", if (stats.defaultRule) "是" else "否")
            StatLine("近期日志匹配", stats.recentLogHits.toString())
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RouteRuleDialog(onDismiss: () -> Unit, onSave: (RouteRule) -> Result<Unit>) {
    var type by remember { mutableStateOf(RouteRuleType.IPCIDRs) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加强制代理规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                ChoiceRow("规则类型", type.label, RouteRuleType.entries.map { it.label }) { selected ->
                    type = RouteRuleType.entries.first { it.label == selected }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = ""
                    },
                    label = { Text("规则") },
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
                        error = "规则不能为空"
                        return@Button
                    }
                    onSave(RouteRule(type, trimmed)).onFailure { err ->
                        error = err.message ?: "保存失败"
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
                title = if (initial.serverHost.isBlank()) "添加配置" else "编辑配置",
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
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = config.serverHost,
                        onValueChange = { config = config.copy(serverHost = it) },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = config.serverPort,
                        onValueChange = { config = config.copy(serverPort = it.filter(Char::isDigit)) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.weight(0.52f),
                    )
                }
                ChoiceRow("协议", config.protocol, AppConfig.Protocols) { config = config.copy(protocol = it) }
                ChoiceRow("Transport", config.transport, AppConfig.Transports) { config = config.copy(transport = it) }
                OutlinedTextField(
                    value = config.token,
                    onValueChange = { config = config.copy(token = it) },
                    label = { Text("UUID / password / token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = config.sni,
                        onValueChange = { config = config.copy(sni = it) },
                        label = { Text("SNI") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = config.path,
                        onValueChange = { config = config.copy(path = it) },
                        label = { Text("Path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = config.tunnelSecurity,
                    onValueChange = { config = config.copy(tunnelSecurity = it.lowercase()) },
                    label = { Text("Security") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.flow,
                    onValueChange = { config = config.copy(flow = it) },
                    label = { Text("Flow") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.realityPublicKey,
                    onValueChange = { config = config.copy(realityPublicKey = it) },
                    label = { Text("REALITY public key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = config.realityFingerprint,
                        onValueChange = { config = config.copy(realityFingerprint = it) },
                        label = { Text("Fingerprint") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = config.realityShortId,
                        onValueChange = { config = config.copy(realityShortId = it) },
                        label = { Text("Short ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = config.realitySpiderX,
                    onValueChange = { config = config.copy(realitySpiderX = it) },
                    label = { Text("SpiderX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow("Upstream", config.upstreamProtocol, AppConfig.UpstreamProtocols) {
                    config = config.copy(upstreamProtocol = it)
                }
                ToggleRow("TLS", config.tls) { config = config.copy(tls = it) }
                ToggleRow("TLS insecure", config.tlsInsecure) { config = config.copy(tlsInsecure = it) }
                ToggleRow("Mux", config.mux) { config = config.copy(mux = it) }
                ToggleRow("UDP", config.udp) { config = config.copy(udp = it) }
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
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            Button(onClick = onSave) {
                Text("保存")
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

private enum class RouteRuleType(val jsonKey: String, val label: String) {
    Domains("domains", "精确域名"),
    DomainRegexes("domain_regexes", "域名正则"),
    DomainSuffixes("domain_suffixes", "域名后缀"),
    IPs("ips", "精确 IP"),
    IPCIDRs("ip_cidrs", "IP CIDR"),
    IPRanges("ip_ranges", "IP 范围"),
}

private data class RouteRule(
    val type: RouteRuleType,
    val value: String,
)

private data class RouteRuleStats(
    val position: Int,
    val typeLabel: String,
    val totalRules: Int,
    val effectiveRules: Int,
    val sameTypeRules: Int,
    val duplicateRules: Int,
    val defaultRule: Boolean,
    val recentLogHits: Int,
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

private fun routeRuleStats(rule: RouteRule, rules: List<RouteRule>, index: Int): RouteRuleStats {
    val defaultRules = parseRouteRules(TcptunVpnService.defaultRouteConfig())
    val logToken = rule.value.lowercase()
    val recentLogHits = if (logToken.length >= 3 && logToken != ".*") {
        TcptunState.logs.count { it.lowercase().contains(logToken) }
    } else {
        0
    }
    return RouteRuleStats(
        position = index + 1,
        typeLabel = rule.type.label,
        totalRules = rules.size,
        effectiveRules = (defaultRules + rules).distinct().size,
        sameTypeRules = rules.count { it.type == rule.type },
        duplicateRules = rules.count { it == rule },
        defaultRule = defaultRules.any { it == rule },
        recentLogHits = recentLogHits,
    )
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

private fun appFilterModeTitle(filter: AppFilterConfig): String {
    return when (filter.mode) {
        AppFilterMode.ProxyAll -> "默认全部应用走代理"
        AppFilterMode.BypassAll -> "仅选中应用走代理"
    }
}

private fun appFilterModeDescription(filter: AppFilterConfig): String {
    return when (filter.mode) {
        AppFilterMode.ProxyAll -> "关闭某个应用后，它会绕过 VPN。"
        AppFilterMode.BypassAll -> "未选中的应用直连；启动前至少保留一个选中应用。"
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
        title = { Text("运行日志") },
        text = {
            val scrollState = rememberScrollState()
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
                            TcptunState.logs.joinToString("\n").ifBlank { "No logs yet." },
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
                Text("关闭")
            }
        },
        dismissButton = {
            TextButton(onClick = TcptunState::clearLogs) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private fun shareProfile(context: Context, profile: AppConfig) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, profile.shareText())
    context.startActivity(Intent.createChooser(send, "分享配置"))
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

private suspend fun tcping(target: TcpingTarget): String = withContext(Dispatchers.IO) {
    val result = tcpingTarget(target)
    val elapsedMs = result.elapsedMs
    if (elapsedMs != null) {
        "TCPing 成功 · ${target.label} ${elapsedMs}ms"
    } else {
        "TCPing 失败 · ${target.label} ${result.error ?: "失败"}"
    }
}

private fun tcpingTarget(target: TcpingTarget): TcpingResult {
    val start = System.nanoTime()
    return runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(TcptunVpnService.LOCAL_SOCKS_HOST, TcptunVpnService.LOCAL_SOCKS_PORT),
                TCPING_TIMEOUT_MS,
            )
            socket.soTimeout = TCPING_TIMEOUT_MS
            socks5Connect(socket, target.host, target.port)
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

private fun socks5Connect(socket: Socket, host: String, port: Int) {
    val input = socket.getInputStream()
    val output = socket.getOutputStream()
    output.write(byteArrayOf(0x05, 0x01, 0x00))
    output.flush()
    val methodReply = input.readExact(2)
    require(methodReply[0] == 0x05.toByte() && methodReply[1] == 0x00.toByte()) {
        "SOCKS5 method rejected"
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
        TcptunState.error(err.message ?: "启动失败")
    }
}

private fun stopVpn(context: Context) {
    TcptunState.setStatus("Stopping")
    TcptunState.appendLog("stop requested")
    runCatching {
        context.startService(TcptunVpnService.stopIntent(context))
    }.onFailure { err ->
        TcptunState.error(err.message ?: "停止失败")
    }
}

private fun isVpnActiveStatus(status: String): Boolean {
    return status == "Starting" || status == "Running" || status == "Stopping"
}

private fun isVpnTransitionStatus(status: String): Boolean {
    return status == "Starting" || status == "Stopping"
}

private fun vpnStatusLabel(status: String): String {
    return when (status) {
        "Starting" -> "启动中"
        "Running" -> "运行中"
        "Stopping" -> "停止中"
        else -> status
    }
}

private fun bridgeTimestampLabel(timestampMs: Long): String {
    if (timestampMs <= 0) return "None"
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.MEDIUM,
    ).format(java.util.Date(timestampMs))
}

private const val TCPING_TIMEOUT_MS = 3_000
private val TCPING_TARGETS = listOf(
    TcpingTarget("Google", "google.com"),
    TcpingTarget("GitHub", "github.com"),
)
