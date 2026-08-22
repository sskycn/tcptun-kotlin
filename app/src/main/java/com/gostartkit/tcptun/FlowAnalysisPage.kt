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

@Composable
internal fun FlowAnalysisPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val resources = LocalResources.current
    val startFailedMessage = stringResource(R.string.start_failed)
    val flowState by TcptunState.flowAnalysis.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(RuntimeSettings()) }
    var installedApps by remember { mutableStateOf<List<InstalledRouteApp>>(emptyList()) }
    var selectionSaving by remember { mutableStateOf(false) }
    var routeRuleSaving by remember { mutableStateOf(false) }
    var showRouteRuleDialog by rememberSaveable { mutableStateOf(false) }
    var routeRuleOutbound by rememberSaveable { mutableStateOf(ManagedRouteOutbound.Proxy) }
    var routeRuleResult by rememberSaveable { mutableStateOf("") }
    var routeRuleError by rememberSaveable { mutableStateOf("") }
    var flowLeaveRequested by remember { mutableStateOf(false) }
    var pageLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flowAnalysisDisabled = stringResource(R.string.flow_analysis_disabled)
    val flowAppOptions = listOf(flowAnalysisDisabled) + installedApps.map(InstalledRouteApp::displayName)
    val selectedPackage = settings.flowAnalysisApp
    val selectedAppLabel = installedApps.firstOrNull { it.packageName == selectedPackage }?.displayName
        ?: selectedPackage.ifBlank { flowAnalysisDisabled }
    val events = flowState.events.asReversed()
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
            try {
                val next = settings.copy(flowAnalysisApp = packageName)
                val persisted = durableMutation(appContext, "flow analysis setting save") {
                    ProcessRuntimeSettingsMutationMutex.withLock {
                        writeUiRuntimeSettings(appContext, next).getOrThrow()
                        applyFlowAnalysisSettings(appContext)
                        readUiRuntimeSettings(appContext)
                    }
                }.await()
                settings = persisted
                if (flowLeaveRequested && !routeRuleSaving) {
                    flowLeaveRequested = false
                    onBack()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                flowLeaveRequested = false
                reportUiError(error.message ?: startFailedMessage)
            } finally {
                selectionSaving = false
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
                durableMutation(appContext, "flow route rule creation") {
                    mutateManagedRouteRules(appContext) { existing ->
                        mergeFlowRouteRuleSuggestions(existing, suggestions)
                    }
                    applyRuntimeSettings(appContext, forceRestart = true)
                }.await()
                routeRuleResult = resources.getString(
                    R.string.flow_analysis_rules_created,
                    suggestions.size,
                )
                showRouteRuleDialog = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                routeRuleError = safeUiErrorMessage(failure.message.orEmpty(), startFailedMessage)
            } finally {
                routeRuleSaving = false
                if (flowLeaveRequested && !selectionSaving) {
                    flowLeaveRequested = false
                    onBack()
                }
            }
        }
    }

    LaunchedEffect(appContext) {
        val loaded = withContext(Dispatchers.IO) {
            ProcessRuntimeSettingsMutationMutex.withLock {
                readUiRuntimeSettings(appContext) to loadInstalledRouteApps(appContext)
            }
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
                    FlowAnalysisMoreMenu(
                        enabled = routeRuleSuggestions.isNotEmpty() && !routeRuleSaving,
                        onCreateRules = { showRouteRuleDialog = true },
                    )
                },
            )
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                val refreshed = ProcessRuntimeSettingsMutationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        readUiRuntimeSettings(appContext) to loadInstalledRouteApps(appContext)
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
                        if (flowState.droppedEvents > 0) {
                            Text(
                                stringResource(R.string.flow_analysis_dropped, flowState.droppedEvents),
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
private fun FlowAnalysisMoreMenu(
    enabled: Boolean,
    onCreateRules: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.more_options),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MenuShape,
            containerColor = colors.surfaceContainer,
        ) {
            DropdownMenuItem(
                enabled = enabled,
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.AltRoute,
                        contentDescription = stringResource(R.string.flow_analysis_create_rules),
                    )
                },
                text = { Text(stringResource(R.string.flow_analysis_create_rules)) },
                onClick = {
                    expanded = false
                    onCreateRules()
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
