package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tcptun.client.ui.theme.TcpTunTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TcpTun)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TcpTunTheme(dynamicColor = false) {
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
    var editing by remember { mutableStateOf<AppConfig?>(null) }
    var showLogs by remember { mutableStateOf(false) }
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

    fun startSelected() {
        val config = state.selected ?: return
        val error = config.validate()
        if (error != null) {
            TcptunState.error(error)
            showLogs = true
            return
        }
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

    Scaffold(
        containerColor = Color.White,
        floatingActionButton = {
            RunFloatingButton(
                running = TcptunState.status.value == "Running" || TcptunState.status.value == "Starting",
                enabled = state.selected != null,
                onClick = {
                    if (TcptunState.status.value == "Running" || TcptunState.status.value == "Starting") {
                        stopVpn(context)
                    } else {
                        startSelected()
                    }
                },
            )
        },
        bottomBar = {
            BottomStatus(
                status = TcptunState.status.value,
                error = TcptunState.lastError.value,
                onClick = { showLogs = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            TopBar(
                onAdd = { editing = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") },
                onImport = ::importFromClipboard,
                onBatterySettings = { openBatteryOptimizationSettings(context) },
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == state.selected?.id,
                        onSelect = { save(state.copy(selectedId = profile.id)) },
                        onShare = { shareProfile(context, profile) },
                        onEdit = { editing = profile },
                        onDelete = {
                            val remaining = state.profiles.filterNot { it.id == profile.id }
                            save(ProfilesState(remaining, remaining.firstOrNull()?.id))
                        },
                    )
                }
                if (state.profiles.isEmpty()) {
                    item {
                        EmptyState(onAdd = { editing = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") })
                    }
                }
            }
        }
    }

    editing?.let { original ->
        EditProfileDialog(
            initial = original,
            onDismiss = { editing = null },
            onSave = { updated ->
                val error = updated.validate()
                if (error != null) {
                    TcptunState.error(error)
                    return@EditProfileDialog
                }
                val profiles = state.profiles.toMutableList()
                val index = profiles.indexOfFirst { it.id == updated.id }
                if (index >= 0) {
                    profiles[index] = updated
                } else {
                    profiles.add(updated)
                }
                save(ProfilesState(profiles, updated.id))
                editing = null
            },
        )
    }

    if (showLogs) {
        LogsDialog(onDismiss = { showLogs = false })
    }
}

@Composable
private fun TopBar(onAdd: () -> Unit, onImport: () -> Unit, onBatterySettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("☰", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.width(24.dp))
        Text("配置项", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(AppIcons.More, contentDescription = "更多", tint = Color.Black)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Import, contentDescription = "从剪切板导入配置", tint = Color.Black)
                            Text("从剪切板导入")
                        }
                    },
                    onClick = {
                        expanded = false
                        onImport()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Add, contentDescription = "添加配置", tint = Color.Black)
                            Text("添加配置")
                        }
                    },
                    onClick = {
                        expanded = false
                        onAdd()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Battery, contentDescription = "省电设置", tint = Color.Black)
                            Text("省电设置")
                        }
                    },
                    onClick = {
                        expanded = false
                        onBatterySettings()
                    },
                )
            }
        }
    }
}

private object AppIcons {
    val More: ImageVector = ImageVector.Builder(
        name = "MoreVertical",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 5f)
            horizontalLineTo(13f)
            verticalLineTo(7f)
            horizontalLineTo(11f)
            close()
            moveTo(11f, 11f)
            horizontalLineTo(13f)
            verticalLineTo(13f)
            horizontalLineTo(11f)
            close()
            moveTo(11f, 17f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            close()
        }
    }.build()

    val Import: ImageVector = ImageVector.Builder(
        name = "Import",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 4f)
            horizontalLineTo(13f)
            verticalLineTo(12f)
            horizontalLineTo(16f)
            lineTo(12f, 16f)
            lineTo(8f, 12f)
            horizontalLineTo(11f)
            close()
            moveTo(5f, 18f)
            horizontalLineTo(19f)
            verticalLineTo(20f)
            horizontalLineTo(5f)
            close()
        }
    }.build()

    val Add: ImageVector = ImageVector.Builder(
        name = "Add",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            close()
        }
    }.build()

    val Battery: ImageVector = ImageVector.Builder(
        name = "Battery",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 8f)
            horizontalLineTo(18f)
            verticalLineTo(16f)
            horizontalLineTo(4f)
            close()
            moveTo(6f, 10f)
            verticalLineTo(14f)
            horizontalLineTo(13f)
            verticalLineTo(10f)
            close()
            moveTo(19f, 10f)
            horizontalLineTo(21f)
            verticalLineTo(14f)
            horizontalLineTo(19f)
            close()
        }
    }.build()
}

@Composable
private fun ProfileRow(
    profile: AppConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(88.dp)
                .background(if (selected) Color.Black else Color.Transparent),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF202124))
            Text(profile.maskedAddress(), style = MaterialTheme.typography.titleLarge, color = Color(0xFF5F6368))
            Text(profile.label(), style = MaterialTheme.typography.titleMedium, color = Color(0xFFD1792E))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onShare) { Text("↗", style = MaterialTheme.typography.headlineMedium, color = Color.Black) }
            TextButton(onClick = onEdit) { Text("✎", style = MaterialTheme.typography.headlineMedium, color = Color.Black) }
            TextButton(onClick = onDelete) { Text("⌫", style = MaterialTheme.typography.headlineMedium, color = Color.Black) }
        }
    }
    HorizontalDivider(color = Color(0xFFE0E0E0))
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("还没有配置项", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onAdd) {
            Text("添加配置")
        }
    }
}

@Composable
private fun RunFloatingButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = Color(0xFFFF7A0A),
        contentColor = Color.White,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.size(88.dp),
    ) {
        Text(
            text = if (running) "■" else "▶",
            style = MaterialTheme.typography.headlineLarge,
            color = if (enabled) Color.White else Color(0xFFFFC092),
        )
    }
}

@Composable
private fun BottomStatus(status: String, error: String, onClick: () -> Unit) {
    val text = when {
        error.isNotBlank() -> "错误：$error"
        status == "Running" -> "已连接，点击测试连接"
        status == "Starting" -> "正在连接..."
        else -> "未连接，选择配置后点击启动"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color(0xFF4A4D52))
    }
}

@Composable
private fun EditProfileDialog(initial: AppConfig, onDismiss: () -> Unit, onSave: (AppConfig) -> Unit) {
    var config by remember(initial.id) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.serverHost.isBlank()) "添加配置" else "编辑配置") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
        },
        confirmButton = {
            Button(onClick = { onSave(config) }) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
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
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志") },
        text = {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState),
            ) {
                SelectionContainer {
                    Text(
                        TcptunState.logs.joinToString("\n").ifBlank { "No logs yet." },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                Text("清空")
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
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

private fun startVpn(context: Context, config: AppConfig) {
    val intent = TcptunVpnService.startIntent(context, config)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

private fun stopVpn(context: Context) {
    context.startService(TcptunVpnService.stopIntent(context))
}
