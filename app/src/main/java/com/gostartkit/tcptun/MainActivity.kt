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
import android.os.PowerManager
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import org.json.JSONArray
import org.json.JSONObject
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
    var editingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var showRouteEditor by remember { mutableStateOf(false) }
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

    val editing = editingProfile
    if (showRouteEditor) {
        RouteConfigPage(
            onBack = { showRouteEditor = false },
        )
    } else if (editing == null) {
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
                    title = "配置项",
                    onAdd = { editingProfile = AppConfig(id = UUID.randomUUID().toString(), name = "proxy") },
                    onImport = ::importFromClipboard,
                    onRouteRules = { showRouteEditor = true },
                    onBatterySettings = { openBatteryOptimizationSettings(context) },
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == state.selected?.id,
                            onSelect = { save(state.copy(selectedId = profile.id)) },
                            onShare = { shareProfile(context, profile) },
                            onEdit = { editingProfile = profile },
                            onDelete = {
                                val remaining = state.profiles.filterNot { it.id == profile.id }
                                save(ProfilesState(remaining, remaining.firstOrNull()?.id))
                            },
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
                save(ProfilesState(profiles, updated.id))
                editingProfile = null
            },
        )
    }

    if (showLogs) {
        LogsDialog(onDismiss = { showLogs = false })
    }
}

@Composable
private fun TopBar(
    title: String,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRouteRules: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
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
                            Icon(AppIcons.Route, contentDescription = "路由规则", tint = Color.Black)
                            Text("路由规则")
                        }
                    },
                    onClick = {
                        expanded = false
                        onRouteRules()
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

    val Route: ImageVector = ImageVector.Builder(
        name = "Route",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 3f)
            horizontalLineTo(8f)
            verticalLineTo(8f)
            horizontalLineTo(3f)
            close()
            moveTo(16f, 16f)
            horizontalLineTo(21f)
            verticalLineTo(21f)
            horizontalLineTo(16f)
            close()
            moveTo(7f, 5f)
            horizontalLineTo(12f)
            verticalLineTo(7f)
            horizontalLineTo(7f)
            close()
            moveTo(10f, 7f)
            horizontalLineTo(12f)
            verticalLineTo(17f)
            horizontalLineTo(17f)
            verticalLineTo(19f)
            horizontalLineTo(10f)
            close()
        }
    }.build()

    val Back: ImageVector = ImageVector.Builder(
        name = "Back",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            verticalLineTo(13f)
            horizontalLineTo(8f)
            lineTo(13f, 18f)
            lineTo(11.6f, 19.4f)
            lineTo(4.2f, 12f)
            lineTo(11.6f, 4.6f)
            lineTo(13f, 6f)
            lineTo(8f, 11f)
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
                message = "已保存，已与自动规则合并生效"
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

    Scaffold(containerColor = Color.White) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            RouteListTopBar(
                title = "路由规则",
                onBack = onBack,
                onAdd = { showAddRule = true },
                onReset = {
                    TcptunVpnService.resetManualRouteConfig(context).fold(
                        onSuccess = {
                            rules = parseRouteRules(TcptunVpnService.readManualRouteConfig(context))
                            message = "已清空手动规则，自动规则仍会生效"
                            error = ""
                        },
                        onFailure = { err ->
                            error = err.message ?: "清空手动规则失败"
                            message = ""
                        },
                    )
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "手动规则 ${rules.size} 条 · 启动时与自动规则合并写入 ${TcptunVpnService.routeConfigFile(context).name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4A4D52),
                )
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1B5E20))
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E))
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun RouteListTopBar(
    title: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.Back, contentDescription = "返回", tint = Color.Black)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) {
            Text("清空手动")
        }
        IconButton(onClick = onAdd) {
            Icon(AppIcons.Add, contentDescription = "添加规则", tint = Color.Black)
        }
    }
}

@Composable
private fun RouteRuleRow(rule: RouteRule, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(56.dp)
                .background(Color(0xFFD1792E)),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(rule.type.label, style = MaterialTheme.typography.titleLarge, color = Color(0xFF202124))
            Text(rule.value, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF4A4D52))
            Text("force_upstream.${rule.type.jsonKey}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF80868B))
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = Color.Black)
    }
    HorizontalDivider(color = Color(0xFFE0E0E0))
}

@Composable
private fun RouteEmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("还没有路由规则", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onAdd) {
            Text("添加规则")
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

    Scaffold(containerColor = Color.White) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            EditTopBar(
                title = "规则详情",
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1B5E20))
                }
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E))
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
                    Text("删除这条规则", color = Color(0xFFB3261E))
                }
            }
        }
    }
}

@Composable
private fun RouteStatsBlock(stats: RouteRuleStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F8FA), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("统计数据", style = MaterialTheme.typography.titleLarge, color = Color(0xFF202124))
        StatLine("位置", "#${stats.position}")
        StatLine("类型", stats.typeLabel)
        StatLine("手动规则总数", stats.totalRules.toString())
        StatLine("合并后有效规则", stats.effectiveRules.toString())
        StatLine("手动同类规则", stats.sameTypeRules.toString())
        StatLine("手动重复规则", stats.duplicateRules.toString())
        StatLine("与自动规则重复", if (stats.defaultRule) "是" else "否")
        StatLine("近期日志匹配", stats.recentLogHits.toString())
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5F6368))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF202124))
    }
}

@Composable
private fun RouteRuleDialog(onDismiss: () -> Unit, onSave: (RouteRule) -> Result<Unit>) {
    var type by remember { mutableStateOf(RouteRuleType.IPCIDRs) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加路由规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E))
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

    Scaffold(containerColor = Color.White) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (formError.isNotBlank()) {
                    Text(formError, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E))
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

@Composable
private fun EditTopBar(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.Back, contentDescription = "返回", tint = Color.Black)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.weight(1f))
        Button(onClick = onSave) {
            Text("保存")
        }
    }
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
    val powerManager = context.getSystemService(PowerManager::class.java)
    val packageName = context.packageName
    val intent = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        powerManager?.isIgnoringBatteryOptimizations(packageName) != true
    ) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
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
