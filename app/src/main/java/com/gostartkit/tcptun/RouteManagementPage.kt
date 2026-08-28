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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val resources = LocalResources.current
    var profileState by remember { mutableStateOf(ProfilesState(emptyList())) }
    val routeProfiles = profileState.profiles.filter { it.rawConfigJson.isBlank() }
    var installedApps by remember { mutableStateOf<List<InstalledRouteApp>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ManagedRouteRule>>(emptyList()) }
    var routeDataLoaded by remember { mutableStateOf(false) }
    var editingRule by rememberSaveable(stateSaver = ManagedRouteRuleSaver) {
        mutableStateOf<ManagedRouteRule?>(null)
    }
    var deleteCandidate by rememberSaveable(stateSaver = ManagedRouteRuleSaver) {
        mutableStateOf<ManagedRouteRule?>(null)
    }
    var routeActionsExpanded by rememberSaveable { mutableStateOf(false) }
    var smartMergePreview by remember { mutableStateOf<SmartRouteMergeResult?>(null) }
    var notice by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var routeSaveCount by remember { mutableIntStateOf(0) }
    var routeLeaveRequested by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var draggedRuleId by remember { mutableStateOf<String?>(null) }
    var draggedRuleOffset by remember { mutableFloatStateOf(0f) }
    var rulesBeforeDrag by remember { mutableStateOf<List<ManagedRouteRule>?>(null) }
    var reorderScrollJob by remember { mutableStateOf<Job?>(null) }
    val reorderScope = rememberCoroutineScope()
    val routeMutationMutex = ProcessRouteRuleMutationMutex
    val reorderScrollStep = with(LocalDensity.current) { 24.dp.toPx() }
    val routeSaving = routeSaveCount > 0
    val routeInteractionEnabled = !routeSaving && !routeLeaveRequested
    val smartMergeResult = remember(rules) { smartMergeManagedRouteRules(rules) }

    LaunchedEffect(appContext) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                appContext.profileRepository().load(appContext),
                loadInstalledRouteApps(appContext),
                RouteRuleStore.load(appContext),
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
            val (persisted, currentProfiles) = durableMutation(appContext, "managed route mutation") {
                val result = mutateManagedRouteRules(appContext, transform)
                applyRuntimeSettings(appContext, forceRestart = true)
                result
            }.await()
            rules = persisted
            profileState = currentProfiles
            dirty = true
            error = ""
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = redactSensitiveText(failure.message.orEmpty().take(MaxUiErrorLength)).trim()
            false
        } finally {
            routeSaveCount = (routeSaveCount - 1).coerceAtLeast(0)
            if (routeLeaveRequested && routeSaveCount == 0) {
                routeLeaveRequested = false
                if (dirty) applyRuntimeSettings(appContext, forceRestart = true)
                onBack()
            }
        }
    }

    fun leave() {
        if (routeSaving) {
            routeLeaveRequested = true
            return
        }
        if (dirty) applyRuntimeSettings(appContext, forceRestart = true)
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
                actions = {
                    RouteActionTopBarMenu(
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
            )
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                val refreshed = routeMutationMutex.withLock {
                    val shouldReloadRules = draggedRuleId == null && !dirty && routeSaveCount == 0
                    withContext(Dispatchers.IO) {
                        Triple(
                            appContext.profileRepository().load(appContext),
                            loadInstalledRouteApps(appContext),
                            if (shouldReloadRules) RouteRuleStore.load(appContext) else null,
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
private fun RouteActionTopBarMenu(
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onSmartMerge: () -> Unit,
) {
    Box {
        SmallFloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(
                    if (expanded) R.string.close_route_actions else R.string.route_actions,
                ),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (expanded) onToggle() },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.smart_merge_route_rules)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Hub,
                        contentDescription = stringResource(R.string.smart_merge_route_rules),
                    )
                },
                enabled = enabled,
                onClick = onSmartMerge,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_route_rule)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.add_route_rule),
                    )
                },
                enabled = enabled,
                onClick = onAdd,
            )
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
            SwipeActionValue.Closed at 0f
            SwipeActionValue.Actions at swipeActionsOffset(
                widthPx = with(density) { (actionWidth * 2).toPx() },
                layoutDirection = layoutDirection,
            )
        }
    }
    val swipeState = remember(rule.id, anchors) {
        AnchoredDraggableState(SwipeActionValue.Closed, anchors)
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
                SwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.edit),
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    enabled = enabled,
                    onClick = onEdit,
                )
                SwipeAction(
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
                            enabled = enabled && swipeState.settledValue == SwipeActionValue.Actions,
                        ) {
                            // Tap only closes an open swipe; edit is via the swipe action.
                            scope.launch { swipeState.animateTo(SwipeActionValue.Closed) }
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
    var type by rememberSaveable(rule.id) { mutableStateOf(rule.type) }
    var value by rememberSaveable(rule.id) {
        mutableStateOf(rule.value.take(MaxManagedRouteRuleValueLength))
    }
    var outbound by rememberSaveable(rule.id) { mutableStateOf(rule.outbound) }
    var outboundProfileId by rememberSaveable(rule.id) { mutableStateOf(rule.outboundProfileId) }
    var enabled by rememberSaveable(rule.id) { mutableStateOf(rule.enabled) }
    var invalid by rememberSaveable(rule.id) { mutableStateOf(false) }
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
internal fun routeRuleTypeLabel(type: ManagedRouteRuleType): String = when (type) {
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

