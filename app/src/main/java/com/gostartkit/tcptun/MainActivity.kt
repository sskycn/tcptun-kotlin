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

internal const val SnackbarAutoDismissMillis = 6_000L
internal const val MaxUiErrorLength = 4_096
private const val SavedProfileIntentSequence = "profileIntentSequence"
private const val SavedPendingProfileUri = "pendingProfileUri"
/** Brief wait after requesting a monitor refresh so pulled UI can show updated values. */
internal const val PullRefreshSettleMillis = 350L
internal const val PostNotificationsPermission = "android.permission.POST_NOTIFICATIONS"

internal val VpnPlanCommandGeneration = AtomicInteger()
internal val VpnPlanCommandJob = AtomicReference<Job?>(null)
internal val UiErrorMessages = Channel<String>(
    capacity = 32,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
internal val ProcessProfileMutationMutex = Mutex()
internal val ProcessRouteRuleMutationMutex = Mutex()
internal val ProcessRuntimeSettingsMutationMutex = Mutex()
internal val QrCodeGenerationMutex = Mutex()
internal const val MaxProfileMutationAttempts = 4
internal const val MaxProfileNameInputLength = 512
internal const val MaxProfileHostInputLength = 2_048
internal const val MaxProfileChoiceInputLength = 256
internal const val MaxRealityKeyInputLength = 4_096
internal const val MaxEchKeyInputLength = 4_096

// TODO(security): replace pending plan/profile SavedState payloads with in-memory IDs so profile
// credentials are not serialized by Android during process recreation.
internal val PendingRunPlanSaver = Saver<ProfileRunPlan?, String>(
    save = { plan -> encodePendingRunPlan(plan) },
    restore = { encoded -> decodePendingRunPlan(encoded) },
)
internal val PendingProfileSaver = Saver<AppConfig?, String>(
    save = { profile -> encodePendingProfile(profile) },
    restore = { encoded -> decodePendingProfile(encoded) },
)
internal val AppConfigSaver = Saver<AppConfig, String>(
    save = { profile -> encodePendingProfile(profile) },
    restore = { encoded -> decodePendingProfile(encoded) },
)
internal val RuntimeSettingsSaver = Saver<RuntimeSettings, String>(
    save = { settings -> encodeRuntimeSettingsSavedState(settings) },
    restore = ::decodeRuntimeSettingsSavedState,
)

internal fun encodeRuntimeSettingsSavedState(settings: RuntimeSettings): String = JSONObject()
    .put("mtu", settings.mtu)
    .put("powerSavingMode", settings.powerSavingMode)
    .put("logLevel", settings.logLevel)
    .put("socksPort", settings.socksPort)
    .put("localProxyProtocol", settings.localProxyProtocol)
    .put("socksListenAll", settings.socksListenAll)
    .put("routeLocalProxyTraffic", settings.routeLocalProxyTraffic)
    .put("defaultOutbound", settings.defaultOutbound)
    .put("flowAnalysisApp", settings.flowAnalysisApp)
    .toString()

internal fun decodeRuntimeSettingsSavedState(encoded: String): RuntimeSettings? = runRecoverableCatching {
    requireSafeJsonNesting(encoded)
    val json = JSONObject(encoded)
    RuntimeSettings(
        mtu = json.optInt("mtu", RuntimeSettingsDefaults.VpnMtu),
        powerSavingMode = json.optBoolean("powerSavingMode", true),
        logLevel = json.optString("logLevel", DefaultLogLevel),
        socksPort = json.optInt("socksPort", RuntimeSettingsDefaults.SocksPort),
        localProxyProtocol = json.optString("localProxyProtocol", DefaultLocalProxyProtocol),
        socksListenAll = json.optBoolean("socksListenAll", false),
        routeLocalProxyTraffic = json.optBoolean("routeLocalProxyTraffic", false),
        defaultOutbound = json.optString("defaultOutbound", DefaultOutboundDynamicPool),
        flowAnalysisApp = json.optString("flowAnalysisApp"),
    )
}.getOrNull()
internal val ManagedRouteRuleSaver = Saver<ManagedRouteRule?, String>(
    save = { rule ->
        rule?.let {
            JSONObject()
                .put("id", it.id)
                .put("type", it.type.name)
                .put("value", it.value)
                .put("outbound", it.outbound.name)
                .put("outboundProfileId", it.outboundProfileId)
                .put("enabled", it.enabled)
                .toString()
        }
    },
    restore = { encoded ->
        runRecoverableCatching {
            requireSafeJsonNesting(encoded)
            val json = JSONObject(encoded)
            ManagedRouteRule(
                id = json.getString("id"),
                type = ManagedRouteRuleType.valueOf(json.getString("type")),
                value = json.getString("value"),
                outbound = ManagedRouteOutbound.valueOf(json.getString("outbound")),
                outboundProfileId = json.optString("outboundProfileId"),
                enabled = json.optBoolean("enabled", true),
            )
        }.getOrNull()
    },
)

internal fun <T> durableMutation(
    context: Context,
    name: String,
    action: suspend CoroutineScope.() -> T,
): Deferred<T> = context.tcptunApplication().durableMutationScope.async(block = action).also { deferred ->
    deferred.invokeOnCompletion { error ->
        if (error != null && error !is CancellationException && !error.isFatalProcessError()) {
            runRecoverableCatching {
                TcptunState.appendLog("$name failed: ${failureDescription(error)}")
            }
        }
    }
}

internal fun Context.tcptunApplication(): TcptunApplication =
    (applicationContext ?: this) as? TcptunApplication
        ?: error("TcpTun must run with TcptunApplication")

internal val CardShapeCompact = RoundedCornerShape(12.dp)
internal val MenuShape = RoundedCornerShape(12.dp)
internal val DialogShape = RoundedCornerShape(28.dp)
internal val QrCardShape = RoundedCornerShape(20.dp)
internal val ListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
internal val ListItemSpacing = 8.dp

internal fun AppConfig.boundedForEditor(): AppConfig = copy(
    name = name.take(MaxProfileNameInputLength),
    serverHost = serverHost.take(MaxProfileHostInputLength),
    serverPort = serverPort.filter(Char::isDigit).take(5),
    protocol = protocol.take(MaxProfileChoiceInputLength),
    transport = transport.take(MaxProfileChoiceInputLength),
    token = token.take(MaxProfileUriLength),
    sni = sni.take(MaxProfileHostInputLength),
    path = path.take(MaxProfileUriLength),
    tunnelSecurity = tunnelSecurity.take(MaxProfileChoiceInputLength),
    flow = flow.take(MaxProfileChoiceInputLength),
    realityPublicKey = realityPublicKey.take(MaxRealityKeyInputLength),
    realityShortId = realityShortId.take(MaxProfileChoiceInputLength),
    realityFingerprint = realityFingerprint.take(MaxProfileChoiceInputLength),
    realitySpiderX = realitySpiderX.take(MaxProfileUriLength),
    echPublicName = echPublicName.take(MaxProfileHostInputLength),
    echPublicKey = echPublicKey.take(MaxEchKeyInputLength),
    echPorts = echPorts.take(MaxProfileChoiceInputLength),
    carrierMode = carrierMode.take(MaxProfileChoiceInputLength),
    carrierUdpMode = carrierUdpMode.take(MaxProfileChoiceInputLength),
    upstreamProtocol = upstreamProtocol.take(MaxProfileChoiceInputLength),
    rawConfigJson = rawConfigJson.take(MaxProfileImportLength),
)

internal fun AppConfig.withoutResumableMux(): AppConfig = copy(
    muxResume = false,
    muxResumeTimeoutMillis = 0,
    muxResumeBufferSize = 0,
)

internal fun AppConfig.withoutEch(): AppConfig = copy(
    echEnabled = false,
    echPublicName = "",
    echPublicKey = "",
    echPorts = "",
)

internal fun reportUiError(message: String) {
    val displayMessage = safeUiErrorMessage(message, "Unknown error")
    TcptunState.appendLog("UI error: $displayMessage")
    UiErrorMessages.trySend(displayMessage)
}

internal fun safeUiErrorMessage(message: String, fallback: String): String =
    redactSensitiveText(message.take(MaxUiErrorLength)).trim().ifBlank { fallback }


class MainActivity : ComponentActivity() {
    private var profileIntentSequence = 0L
    private var pendingProfileUri by mutableStateOf<PendingProfileUri?>(null)
    private var uiVisibilityLease: UiVisibilityLease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_TcpTun)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleProfileIntent(intent)
        } else {
            profileIntentSequence = savedInstanceState.getLong(SavedProfileIntentSequence, 0L)
            savedInstanceState.getString(SavedPendingProfileUri)
                ?.takeIf { it.length <= MaxProfileUriLength }
                ?.let { value -> pendingProfileUri = PendingProfileUri(profileIntentSequence, value) }
        }
        enableEdgeToEdge()
        val benchmarkDestination = if (BuildConfig.BENCHMARK && intent.getStringExtra("benchmarkDestination") == "flow") {
            MainDestination.FlowAnalysis
        } else {
            MainDestination.Profiles
        }
        setContent {
            TcpTunTheme {
                TcptunScreen(
                    initialDestination = benchmarkDestination,
                    pendingProfileUri = pendingProfileUri,
                    onProfileUriConsumed = { sequence ->
                        if (pendingProfileUri?.sequence == sequence) pendingProfileUri = null
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (uiVisibilityLease == null) {
            uiVisibilityLease = TcptunState.acquireUiVisibility()
        }
        if (TcptunState.status == VpnStatus.Running) {
            TcptunVpnService.requestUiVisibleHealthCheck()
        }
    }

    override fun onStop() {
        releaseUiVisibility()
        super.onStop()
    }

    override fun onDestroy() {
        // Defensive for non-standard framework/test teardown that skips onStop.
        releaseUiVisibility()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProfileIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(SavedProfileIntentSequence, profileIntentSequence)
        pendingProfileUri?.value?.let { value -> outState.putString(SavedPendingProfileUri, value) }
        super.onSaveInstanceState(outState)
    }

    private fun handleProfileIntent(intent: Intent?) {
        val value = profileUriFromIntent(intent) ?: return
        pendingProfileUri = PendingProfileUri(++profileIntentSequence, value)
    }

    private fun releaseUiVisibility() {
        uiVisibilityLease?.close()
        uiVisibilityLease = null
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.settings),
        onBack = onBack,
    )
}

@Composable
internal fun LogsDialog(onDismiss: () -> Unit) {
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
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

internal fun shareProfile(context: Context, uri: String) {
    runRecoverableCatching {
        context.startActivity(
            Intent.createChooser(createProfileShareIntent(uri), context.getString(R.string.share_profile)),
        )
    }.onFailure { error ->
        reportUiError(error.message ?: context.getString(R.string.share_profile_failed))
    }
}

internal fun createProfileShareIntent(profile: AppConfig): Intent {
    val uri = requireNotNull(ProfileUriCodec.encode(profile)) { "profile cannot be encoded as a URI" }
    return createProfileShareIntent(uri)
}

internal fun createProfileShareIntent(uri: String): Intent {
    require(uri.isNotBlank() && uri.length <= MaxProfileUriLength) { "invalid profile URI" }
    return Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, uri)
}

internal data class TcpingTarget(
    val label: String,
    val host: String,
    val port: Int = 443,
)

internal fun clipboardText(context: Context): Result<String> = runRecoverableCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return@runRecoverableCatching ""
    val clip = clipboard.primaryClip ?: return@runRecoverableCatching ""
    if (clip.itemCount == 0) return@runRecoverableCatching ""
    val item = clip.getItemAt(0)
    item.text?.let { value ->
        require(value.length <= MaxProfileImportLength) { "clipboard profile data is too large" }
        return@runRecoverableCatching value.toString()
    }
    val uri = item.uri ?: return@runRecoverableCatching ""
    val scheme = uri.scheme?.lowercase().orEmpty()
    require(scheme in setOf("content", "android.resource", "file")) { "unsupported clipboard URI" }
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("clipboard URI could not be opened")
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val output = StringBuilder(minOf(MaxProfileImportLength, 4_096))
        val buffer = CharArray(4_096)
        while (true) {
            val remaining = MaxProfileImportLength + 1 - output.length
            if (remaining <= 0) error("clipboard profile data is too large")
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) {
                val character = reader.read()
                if (character < 0) break
                output.append(character.toChar())
            } else {
                output.append(buffer, 0, count)
            }
        }
        require(output.length <= MaxProfileImportLength) { "clipboard profile data is too large" }
        output.toString()
    }
}

internal fun clearClipboardText(context: Context, expectedText: String) {
    runRecoverableCatching {
        val currentValue = clipboardText(context).getOrNull() ?: return@runRecoverableCatching
        if (currentValue.trim() != expectedText) return@runRecoverableCatching
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@runRecoverableCatching

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }.onFailure { error ->
        TcptunState.appendLog("clipboard clear failed: ${error.message ?: error.javaClass.simpleName}")
    }
}

internal fun readUiRuntimeSettings(context: Context): RuntimeSettings {
    return runRecoverableCatching { RuntimeSettingsRepository.read(context) }
        .getOrElse { error ->
            TcptunState.appendLog(
                "runtime settings read failed: ${error.message ?: error.javaClass.simpleName}",
            )
            RuntimeSettings()
        }
}

internal fun writeUiRuntimeSettings(context: Context, settings: RuntimeSettings): Result<Unit> {
    return runRecoverableCatching { RuntimeSettingsRepository.write(context, settings) }
}

internal fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return runRecoverableCatching {
        ContextCompat.checkSelfPermission(context, PostNotificationsPermission) !=
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)
}

internal fun startVpn(context: Context, plan: ProfileRunPlan) {
    val appContext = context.applicationContext ?: context
    val dispatchFailureMessage = appContext.getString(R.string.start_failed)
    TcptunState.setStatus(VpnStatus.Starting)
    TcptunState.setConnectionsReady(false)
    TcptunState.appendLog("start requested")
    enqueueVpnPlanCommand(
        context = appContext,
        plan = plan,
        updateOnly = false,
        onDispatchFailure = { message ->
            val safeMessage = message.ifBlank { dispatchFailureMessage }
            TcptunState.errorIfStatus(VpnStatus.Starting, safeMessage)
            reportUiError(safeMessage)
            try {
                rollbackInitialStartAfterDispatchFailure(appContext, plan)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error.isFatalProcessError()) throw error
                TcptunState.appendLog(
                    "failed VPN start rollback failed: ${failureDescription(error)}",
                )
            }
        },
    )
}

/**
 * A foreground-service dispatch can fail after UI state was committed (for
 * example process/background restrictions). Roll back only when the exact
 * plan is still authoritative; a newer profile mutation always wins.
 */
internal suspend fun rollbackInitialStartAfterDispatchFailure(
    context: Context,
    plan: ProfileRunPlan,
): Boolean = withContext(NonCancellable) {
    ProcessProfileMutationMutex.withLock {
        repeat(MaxProfileMutationAttempts) {
            val repository = context.profileRepository()
            val snapshot = withContext(Dispatchers.IO) { repository.snapshot(context) }
            val current = snapshot.requireAuthoritativeState()
            if (!shouldRollbackFailedInitialStart(current, plan)) return@withLock false
            val restored = withContext(Dispatchers.IO) {
                repository.saveIfCurrent(
                    context = context,
                    expected = snapshot,
                    next = current.copy(activeIds = emptySet()),
                ).getOrThrow()
            }
            if (restored != null) {
                TcptunState.notifyProfileStateChanged()
                TcptunState.appendLog("active profiles rolled back after VPN start dispatch failed")
                return@withLock true
            }
        }
        throw IllegalStateException("profile state changed repeatedly while rolling back failed VPN start")
    }
}

internal fun stopVpn(context: Context): Boolean {
    val previous = TcptunState.state.value
    var dispatched = true
    VpnPlanCommandGeneration.incrementAndGet()
    VpnPlanCommandJob.getAndSet(null)?.cancel()
    TcptunState.setStatus(VpnStatus.Stopping)
    TcptunState.setConnectionsReady(false)
    TcptunState.appendLog("stop requested")
    runRecoverableCatching {
        context.startService(TcptunVpnService.stopIntent(context))
    }.onFailure { err ->
        dispatched = false
        val message = err.message ?: context.getString(R.string.stop_failed)
        reportUiError(message)
        TcptunState.restoreCommandStateIfStatus(
            expectedStatus = VpnStatus.Stopping,
            restoredStatus = previous.status,
            restoredConnectionsReady = previous.connectionsReady,
            restoredLastError = previous.lastError,
        )
    }
    return dispatched
}

internal suspend fun updateVpnOutbounds(
    context: Context,
    plan: ProfileRunPlan,
): Boolean {
    // Disable TCPing until StartOutbound/StopOutbound finishes for every changed link.
    val previousReady = TcptunState.state.value.connectionsReady
    TcptunState.markConnectionsBusy("connection update requested")
    return try {
        val appContext = context.applicationContext ?: context
        val intent = withContext(Dispatchers.IO) {
            TcptunVpnService.updateOutboundsIntent(appContext, plan)
        }
        appContext.startService(intent)
        true
    } catch (cancelled: CancellationException) {
        TcptunState.restoreConnectionsReadyIfStatus(VpnStatus.Running, previousReady)
        throw cancelled
    } catch (error: Throwable) {
        if (error.isFatalProcessError()) throw error
        reportUiError(error.message ?: context.getString(R.string.start_failed))
        TcptunState.restoreConnectionsReadyIfStatus(VpnStatus.Running, previousReady)
        false
    }
}

internal fun enqueueVpnPlanCommand(
    context: Context,
    plan: ProfileRunPlan,
    updateOnly: Boolean,
    onDispatchSuccess: () -> Unit = {},
    onDispatchFailure: suspend (String) -> Unit,
) {
    val appContext = context.applicationContext ?: context
    val generation = VpnPlanCommandGeneration.incrementAndGet()
    val job = appContext.tcptunApplication().vpnPlanCommandScope.launch {
        val intent = try {
            withContext(Dispatchers.IO) {
                if (updateOnly) {
                    TcptunVpnService.updateOutboundsIntent(appContext, plan)
                } else {
                    TcptunVpnService.startIntent(appContext, plan)
                }
            }
        } catch (_: CancellationException) {
            return@launch
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (generation == VpnPlanCommandGeneration.get()) {
                onDispatchFailure(error.message ?: appContext.getString(R.string.start_failed))
            }
            return@launch
        }
        if (generation != VpnPlanCommandGeneration.get()) return@launch
        try {
            if (!updateOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            if (generation == VpnPlanCommandGeneration.get()) onDispatchSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isFatalProcessError()) throw error
            if (generation == VpnPlanCommandGeneration.get()) {
                onDispatchFailure(error.message ?: appContext.getString(R.string.start_failed))
            }
        }
    }
    VpnPlanCommandJob.getAndSet(job)?.cancel()
    job.invokeOnCompletion {
        VpnPlanCommandJob.compareAndSet(job, null)
    }
}

internal fun applyRuntimeSettings(context: Context, forceRestart: Boolean = false) {
    val status = TcptunState.status
    if (status != VpnStatus.Starting && status != VpnStatus.Running) return
    runRecoverableCatching {
        context.startService(
            TcptunVpnService.applyRuntimeSettingsIntent(context, forceRestart = forceRestart),
        )
    }.onFailure { err ->
        TcptunState.appendLog("runtime settings apply request failed: ${err.message}")
    }
}

internal fun applyFlowAnalysisSettings(context: Context) {
    val status = TcptunState.status
    if (status != VpnStatus.Starting && status != VpnStatus.Running) return
    runRecoverableCatching {
        context.startService(TcptunVpnService.updateFlowAnalysisIntent(context))
    }.onFailure { err ->
        TcptunState.appendLog("flow analysis update request failed: ${err.message}")
    }
}

internal fun isVpnActiveStatus(status: VpnStatus): Boolean = status.isActive

internal fun nextActiveProfileIds(
    activeIds: Set<String>,
    profileId: String,
    vpnStatus: VpnStatus,
): Set<String> {
    val profileIsRunning = profileId in activeIds && isVpnActiveStatus(vpnStatus)
    return if (profileIsRunning) activeIds - profileId else activeIds + profileId
}

internal fun rollbackProfileStateIfStillCommitted(
    current: ProfilesState,
    currentRevision: Long,
    committed: ProfilesState,
    committedRevision: Long,
    previous: ProfilesState,
): ProfilesState? = previous.takeIf {
    currentRevision == committedRevision && current == committed
}

internal fun shouldRollbackFailedInitialStart(
    current: ProfilesState,
    failedPlan: ProfileRunPlan,
): Boolean = current.activeIds.isNotEmpty() &&
    runRecoverableCatching { current.runPlan() == failedPlan }.getOrDefault(false)

internal fun canStartTcping(
    status: VpnStatus,
    activeProfileCount: Int,
    connectionsReady: Boolean = true,
): Boolean = status == VpnStatus.Running && activeProfileCount > 0 && connectionsReady

internal fun isVpnTransitionStatus(status: VpnStatus): Boolean = status.isTransitioning

internal fun bridgeTimestampLabel(timestampMs: Long, noneLabel: String): String {
    if (timestampMs <= 0) return noneLabel
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.MEDIUM,
    ).format(java.util.Date(timestampMs))
}

internal val TCPING_TARGETS = listOf(
    TcpingTarget("Google", "google.com"),
    TcpingTarget("GitHub", "github.com"),
    TcpingTarget("Cloudflare", "cloudflare.com"),
)
