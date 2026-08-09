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
internal fun ProfileListHeader(
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
internal fun AutoDismissSnackbarHost(hostState: SnackbarHostState) {
    val snackbarData = hostState.currentSnackbarData
    LaunchedEffect(snackbarData) {
        if (snackbarData != null && snackbarData.visuals.actionLabel == null) {
            delay(SnackbarAutoDismissMillis)
            if (hostState.currentSnackbarData === snackbarData) snackbarData.dismiss()
        }
    }
    SnackbarHost(hostState)
}

internal suspend fun SnackbarHostState.showDismissibleSnackbar(
    message: String,
    actionLabel: String? = null,
): SnackbarResult = showSnackbar(
    message = message,
    actionLabel = actionLabel,
    withDismissAction = true,
    duration = SnackbarDuration.Indefinite,
)

@Composable
internal fun ConfirmProfileImportDialog(
    profile: AppConfig,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = rememberProfilePresentation(profile)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_profile_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(presentation.maskedAddress, style = MaterialTheme.typography.bodyLarge)
                Text(
                    presentation.label,
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
            Button(onClick = onConfirm, enabled = enabled) {
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
internal fun TopBar(
    title: String,
    actionsEnabled: Boolean,
    onDiagnostics: () -> Unit,
    onRouteManagement: () -> Unit,
    onFlowAnalysis: () -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
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
                        enabled = actionsEnabled,
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
                        enabled = actionsEnabled,
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
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Speed, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.diagnostics)) },
                        onClick = {
                            menuExpanded = false
                            onDiagnostics()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.onSurface,
                            leadingIconColor = colors.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Rounded.Hub, contentDescription = null)
                        },
                        text = { Text(stringResource(R.string.flow_analysis)) },
                        onClick = {
                            menuExpanded = false
                            onFlowAnalysis()
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

internal data class ProfilePresentation(
    val label: String,
    val maskedAddress: String,
    val shareUri: String?,
)

@Composable
internal fun rememberProfilePresentation(profile: AppConfig): ProfilePresentation {
    val initial = remember(profile) {
        ProfilePresentation(
            label = profile.label(),
            maskedAddress = if (profile.rawConfigJson.isBlank()) profile.maskedAddress() else "",
            shareUri = null,
        )
    }
    val presentation by produceState(initialValue = initial, profile) {
        value = withContext(Dispatchers.Default) {
            ProfilePresentation(
                label = profile.label(),
                maskedAddress = profile.maskedAddress(),
                shareUri = ProfileUriCodec.encode(profile),
            )
        }
    }
    return presentation
}

@Composable
internal fun ProfileRow(
    modifier: Modifier = Modifier,
    profile: AppConfig,
    running: Boolean,
    health: ProfileHealth?,
    enabled: Boolean,
    onClick: () -> Unit,
    onShare: (String) -> Unit,
    onShowQrCode: () -> Unit,
    onEdit: () -> Unit,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val presentation = rememberProfilePresentation(profile)
    val shareable = presentation.shareUri != null
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
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val actionWidth = 80.dp
    val anchors = remember(density, layoutDirection) {
        DraggableAnchors {
            ProfileSwipeValue.Closed at 0f
            ProfileSwipeValue.Actions at swipeActionsOffset(
                widthPx = with(density) { (actionWidth * 2).toPx() },
                layoutDirection = layoutDirection,
            )
        }
    }
    val swipeState = remember(profile.id, anchors) {
        AnchoredDraggableState(ProfileSwipeValue.Closed, anchors)
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
                ProfileSwipeAction(
                    modifier = Modifier.width(actionWidth),
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.edit),
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    enabled = enabled,
                    onClick = onEdit,
                )
                ProfileSwipeAction(
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
                color = rowColor,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .clickable(enabled = enabled) {
                            if (swipeState.settledValue == ProfileSwipeValue.Actions) {
                                scope.launch { swipeState.animateTo(ProfileSwipeValue.Closed) }
                            } else {
                                onClick()
                            }
                        }
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
                            text = presentation.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            presentation.maskedAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryContentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (degraded && !health?.error.isNullOrBlank()) {
                            Text(
                                profileHealthErrorSummary(health?.error.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryContentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    IconButton(
                        onClick = { presentation.shareUri?.let(onShare) },
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
                    contentDescription = stringResource(R.string.reorder_profile),
                    tint = secondaryContentColor,
                )
            }
            Spacer(Modifier.width(96.dp))
        }
    }
}

internal enum class ProfileSwipeValue {
    Closed,
    Actions,
}

internal fun <T> AnchoredDraggableState<T>.safeOffset(): Float = offset.takeIf(Float::isFinite) ?: 0f

internal fun swipeActionsOffset(widthPx: Float, layoutDirection: LayoutDirection): Float {
    return if (layoutDirection == LayoutDirection.Ltr) -widthPx else widthPx
}

@Composable
internal fun ProfileSwipeAction(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
internal fun ProfileStatusMark(running: Boolean, degraded: Boolean) {
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
internal fun profileHealthLabel(health: ProfileHealth?): String? {
    return when (health?.status ?: ProfileHealthStatus.Unknown) {
        ProfileHealthStatus.Unknown -> "…"
        ProfileHealthStatus.Healthy -> health?.latencyMs?.let { latency ->
            stringResource(R.string.profile_health_latency, latency)
        }
        ProfileHealthStatus.Degraded -> {
            val failures = (health?.failures ?: 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.profile_health_degraded_failures, failures, failures)
        }
    }
}

internal fun profileHealthErrorSummary(error: String): String {
    Regex("""close called for canceled stream \d+""").find(error)?.let { return it.value }
    Regex("""mux session dial is backing off for native QUIC \([^)]*\)""")
        .find(error)
        ?.let { return it.value }
    return error.substringAfterLast("; ").substringAfterLast(": ").trim().take(160)
}

@Composable
internal fun ProfileQrCodeDialog(
    profile: AppConfig,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bitmapResult by produceState<Result<Bitmap>?>(initialValue = null, profile) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerationMutex.withLock {
                currentCoroutineContext().ensureActive()
                val result = runRecoverableCatching {
                    val png = ProfileUriCodec.encodeQrCode(profile)
                        ?: throw IllegalArgumentException("profile cannot be encoded as a QR code")
                    decodeQrCodeBitmap(png)
                }
                currentCoroutineContext().ensureActive()
                result
            }
        }
    }
    if (bitmapResult == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_qr_code)) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )
        return
    }
    val bitmap = bitmapResult?.getOrNull()
    if (bitmap == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_qr_code)) },
            text = { Text(stringResource(R.string.profile_qr_code_failed)) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            },
        )
        return
    }

    val profileMeta = listOf(profile.label(), profile.maskedAddress())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DialogShape,
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = colors.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.profile_qr_code),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )

                if (profileMeta.isNotBlank()) {
                    Text(
                        text = profileMeta,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Flat pure-white stage (no elevation shadow under the code) for cleaner captures.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    shape = QrCardShape,
                    color = Color.White,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.profile_qr_code_description,
                            profile.name,
                        ),
                        contentScale = ContentScale.Fit,
                        // Nearest-neighbor keeps module edges crisp when Compose scales the bitmap.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            // Extra light margin beyond the encoded quiet zone helps camera framing.
                            .padding(24.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.profile_qr_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                )

                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(enabled: Boolean, onAdd: () -> Unit) {
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
        FilledTonalButton(onClick = onAdd, enabled = enabled) {
            Text(stringResource(R.string.add_profile))
        }
    }
}

@Composable
internal fun BottomStatus(
    status: VpnStatus,
    error: String,
    tcping: TcpingProgress,
    hasProfiles: Boolean,
    connectionsReady: Boolean,
    tcpingEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tcpingMessage = tcpingStatusText(tcping)
    val connectionsStarting = status == VpnStatus.Starting ||
        (status == VpnStatus.Running && !connectionsReady)
    val text = when {
        error.isNotBlank() -> stringResource(R.string.error_prefix, error)
        tcpingMessage.isNotBlank() -> tcpingMessage
        status == VpnStatus.Running && tcpingEnabled -> stringResource(R.string.connected_tap_test)
        connectionsStarting -> stringResource(R.string.connecting)
        status == VpnStatus.Stopping -> stringResource(R.string.stopping)
        hasProfiles -> stringResource(R.string.not_connected_tap_profile)
        else -> stringResource(R.string.not_connected_add_profile)
    }
    val contentColor = when {
        error.isNotBlank() -> colors.error
        tcping.running -> colors.tertiary
        tcping.error.isNotBlank() && tcping.results.isEmpty() -> colors.error
        status == VpnStatus.Running && tcpingEnabled -> colors.primary
        tcping.results.any { it.elapsedMs != null } && status == VpnStatus.Running -> colors.primary
        connectionsStarting || status == VpnStatus.Stopping -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    val statusIcon = when {
        error.isNotBlank() -> Icons.Rounded.Speed
        status == VpnStatus.Running && connectionsReady -> Icons.Rounded.Check
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
internal fun tcpingStatusText(progress: TcpingProgress): String {
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


