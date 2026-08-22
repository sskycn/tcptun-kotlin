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
internal fun TcptunScreen(
    initialDestination: MainDestination = MainDestination.Profiles,
    pendingProfileUri: PendingProfileUri? = null,
    onProfileUriConsumed: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val profileRepository = remember(appContext) { appContext.profileRepository() }
    val resources = LocalResources.current
    val emptyClipboard = stringResource(R.string.empty_clipboard)
    val invalidClipboard = stringResource(R.string.invalid_clipboard_data)
    val undoLabel = stringResource(R.string.undo)
    var storedState by remember { mutableStateOf<ProfilesState?>(null) }
    var pendingDeepLinkProfile by rememberSaveable(stateSaver = PendingProfileSaver) {
        mutableStateOf<AppConfig?>(null)
    }
    var pendingConfig by rememberSaveable(stateSaver = PendingRunPlanSaver) {
        mutableStateOf<ProfileRunPlan?>(null)
    }
    var pendingNotificationConfig by rememberSaveable(stateSaver = PendingRunPlanSaver) {
        mutableStateOf<ProfileRunPlan?>(null)
    }
    var editingProfile by rememberSaveable(stateSaver = PendingProfileSaver) {
        mutableStateOf<AppConfig?>(null)
    }
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    var scannerSessionGeneration by rememberSaveable { mutableIntStateOf(0) }
    var scannerImportJob by remember { mutableStateOf<Job?>(null) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var profileQrCode by rememberSaveable(stateSaver = PendingProfileSaver) {
        mutableStateOf<AppConfig?>(null)
    }
    var tcpingTargetIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarHostState) {
        for (message in UiErrorMessages) {
            snackbarHostState.showDismissibleSnackbar(message)
        }
    }
    val screenScope = rememberCoroutineScope()
    val profileMutationMutex = ProcessProfileMutationMutex
    val profileReloadGeneration = remember { AtomicInteger() }
    val profileListState = rememberLazyListState()
    var draggedProfileId by remember { mutableStateOf<String?>(null) }
    var draggedProfileOffset by remember { mutableFloatStateOf(0f) }
    var profilesBeforeDrag by remember { mutableStateOf<List<AppConfig>?>(null) }
    var profileReorderScrollJob by remember { mutableStateOf<Job?>(null) }
    val profileReorderScrollStep = with(LocalDensity.current) { 24.dp.toPx() }
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    LaunchedEffect(appContext) {
        val generation = profileReloadGeneration.incrementAndGet()
        val loaded = profileMutationMutex.withLock {
            withContext(Dispatchers.IO) { profileRepository.load(appContext) }
        }
        if (generation == profileReloadGeneration.get()) storedState = loaded
    }
    LaunchedEffect(vpnState.status) {
        if (vpnState.status.isTerminal) {
            delay(100)
            val generation = profileReloadGeneration.incrementAndGet()
            val loaded = profileMutationMutex.withLock {
                withContext(Dispatchers.IO) { profileRepository.load(appContext) }
            }
            if (generation == profileReloadGeneration.get()) storedState = loaded
        }
    }
    LaunchedEffect(vpnState.profileStateRevision) {
        if (vpnState.profileStateRevision > 0) {
            val generation = profileReloadGeneration.incrementAndGet()
            val loaded = profileMutationMutex.withLock {
                withContext(Dispatchers.IO) { profileRepository.load(appContext) }
            }
            if (generation == profileReloadGeneration.get()) storedState = loaded
        }
    }
    val state = storedState
    if (state == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    fun requestVpnStart(plan: ProfileRunPlan) {
        startVpn(context, plan)
    }
    fun failPendingVpnStart(plan: ProfileRunPlan, message: String) {
        reportUiError(message)
        appContext.tcptunApplication().vpnPlanCommandScope.launch {
            runRecoverableCatching {
                rollbackInitialStartAfterDispatchFailure(context.applicationContext, plan)
            }.onFailure { rollbackError ->
                TcptunState.appendLog(
                    "failed VPN start rollback failed: ${failureDescription(rollbackError)}",
                )
            }
        }
    }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val plan = pendingConfig
        pendingConfig = null
        if (plan != null) {
            if (result.resultCode == Activity.RESULT_OK) {
                requestVpnStart(plan)
            } else {
                failPendingVpnStart(plan, resources.getString(R.string.start_failed))
            }
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val plan = pendingNotificationConfig ?: return@rememberLauncherForActivityResult
        pendingNotificationConfig = null
        if (!granted) {
            TcptunState.appendLog("notification permission denied; foreground notification may be hidden")
        }
        runRecoverableCatching { VpnService.prepare(context) }.fold(
            onSuccess = { prepare ->
                if (prepare == null) {
                    requestVpnStart(plan)
                } else {
                    pendingConfig = plan
                    runRecoverableCatching { vpnLauncher.launch(prepare) }.onFailure { error ->
                        pendingConfig = null
                        failPendingVpnStart(
                            plan,
                            error.message ?: resources.getString(R.string.start_failed),
                        )
                    }
                }
            },
            onFailure = { error ->
                failPendingVpnStart(
                    plan,
                    error.message ?: resources.getString(R.string.start_failed),
                )
            },
        )
    }
    fun requireProfileMutationAllowed() {
        check(!isVpnTransitionStatus(TcptunState.status)) {
            appContext.getString(R.string.profile_save_failed)
        }
    }
    suspend fun commitProfileMutationLocked(
        transform: (ProfilesState) -> ProfilesState,
        validate: suspend (ProfilesState, ProfilesState) -> Unit = { _, _ -> },
    ): Pair<ProfilesState, ProfileStoreSnapshot> {
        repeat(MaxProfileMutationAttempts) {
            requireProfileMutationAllowed()
            val snapshot = withContext(Dispatchers.IO) { profileRepository.snapshot(appContext) }
            val next = withContext(Dispatchers.Default) { transform(snapshot.state) }
            validate(snapshot.state, next)
            requireProfileMutationAllowed()
            val saved = withContext(Dispatchers.IO) {
                profileRepository.saveIfCurrent(appContext, snapshot, next).getOrThrow()
            }
            if (saved != null) {
                TcptunState.notifyProfileStateChanged()
                return snapshot.state to saved
            }
        }
        throw IllegalStateException("profile state changed repeatedly; please retry")
    }

    suspend fun saveProfileMutation(
        transform: (ProfilesState) -> ProfilesState,
    ): ProfilesState? {
        return try {
            durableMutation(appContext, "profile save") {
                profileMutationMutex.withLock {
                    commitProfileMutationLocked(transform).second.state
                }
            }.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiError(error.message ?: resources.getString(R.string.profile_save_failed))
            null
        }
    }

    suspend fun rollbackCommittedProfileMutation(
        committed: ProfileStoreSnapshot,
        previous: ProfilesState,
    ): Boolean {
        repeat(MaxProfileMutationAttempts) {
            val snapshot = withContext(Dispatchers.IO) { profileRepository.snapshot(appContext) }
            val currentState = snapshot.requireAuthoritativeState()
            val rollback = rollbackProfileStateIfStillCommitted(
                current = currentState,
                currentRevision = snapshot.mutationRevision,
                committed = committed.state,
                committedRevision = committed.mutationRevision,
                previous = previous,
            ) ?: run {
                TcptunState.appendLog(
                    "profile rollback skipped because a newer profile state is already stored",
                )
                return false
            }
            val restored = withContext(Dispatchers.IO) {
                profileRepository.saveIfCurrent(appContext, snapshot, rollback).getOrThrow()
            }
            if (restored != null) {
                TcptunState.notifyProfileStateChanged()
                TcptunState.appendLog("profile state rolled back after VPN command dispatch failed")
                return true
            }
        }
        throw IllegalStateException("profile state changed repeatedly while rolling back")
    }

    suspend fun decodeValidatedProfile(raw: String): AppConfig = withContext(Dispatchers.Default) {
        ProfileUriCodec.decode(raw).getOrThrow().also(::validateImportedProfile)
    }

    suspend fun storeValidatedProfile(profile: AppConfig): Pair<AppConfig, Boolean> =
        durableMutation(appContext, "scanned profile save") {
            profileMutationMutex.withLock {
                val identity = withContext(Dispatchers.Default) {
                    profileConnectionIdentity(profile)
                        ?: throw IllegalArgumentException("profile identity could not be created")
                }
                var storedProfile = profile
                var added = false
                commitProfileMutationLocked(transform = { current ->
                    current.profiles.firstOrNull { candidate ->
                        profileConnectionIdentity(candidate) == identity
                    }?.let { existing ->
                        storedProfile = existing
                        added = false
                        current
                    } ?: current.copy(profiles = current.profiles + profile).also {
                        storedProfile = profile
                        added = true
                    }
                })
                storedProfile to added
            }
        }.await()

    fun openQrScanner() {
        if (isVpnTransitionStatus(vpnState.status)) return
        scannerImportJob?.cancel()
        scannerImportJob = null
        scannerSessionGeneration += 1
        destination = MainDestination.QrScanner
    }

    fun closeQrScanner() {
        scannerSessionGeneration += 1
        scannerImportJob?.cancel()
        scannerImportJob = null
        destination = MainDestination.Profiles
    }

    LaunchedEffect(pendingProfileUri?.sequence) {
        val pending = pendingProfileUri ?: return@LaunchedEffect
        try {
            val profile = decodeValidatedProfile(pending.value)
            val isDeepLink = withContext(Dispatchers.Default) {
                ProfileDeepLinkCodec.isSupportedLink(pending.value)
            }
            if (isDeepLink) {
                pendingDeepLinkProfile = profile
            } else {
                val (storedProfile, added) = storeValidatedProfile(profile)
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
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            try {
                val link = withContext(Dispatchers.IO) { clipboardText(context).getOrThrow() }.trim()
                if (link.isBlank()) {
                    reportUiError(emptyClipboard)
                    return@launch
                }
                val profile = decodeValidatedProfile(link)
                storeValidatedProfile(profile)
                withContext(Dispatchers.IO) { clearClipboardText(context, link) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportUiError(invalidClipboard)
            }
        }
    }

    fun importScannedProfile(link: String, onComplete: (Boolean) -> Unit) {
        val generation = scannerSessionGeneration
        scannerImportJob?.cancel()
        scannerImportJob = screenScope.launch {
            try {
                val profile = decodeValidatedProfile(link.trim())
                if (generation != scannerSessionGeneration || destination != MainDestination.QrScanner) return@launch
                val (storedProfile, added) = storeValidatedProfile(profile)
                if (generation != scannerSessionGeneration || destination != MainDestination.QrScanner) return@launch
                onComplete(true)
                destination = MainDestination.Profiles
                scannerSessionGeneration += 1
                scannerImportJob = null
                val message = if (added) {
                    resources.getString(R.string.profile_imported, storedProfile.name)
                } else {
                    resources.getString(R.string.profile_already_exists, storedProfile.name)
                }
                snackbarHostState.showDismissibleSnackbar(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == scannerSessionGeneration && destination == MainDestination.QrScanner) onComplete(false)
            } finally {
                if (generation == scannerSessionGeneration) scannerImportJob = null
            }
        }
    }

    fun launchPlan(plan: ProfileRunPlan) {
        TcptunState.clearLogs()
        if (needsNotificationPermission(context)) {
            pendingNotificationConfig = plan
            runRecoverableCatching { notificationLauncher.launch(PostNotificationsPermission) }
                .onFailure { error ->
                    pendingNotificationConfig = null
                    failPendingVpnStart(
                        plan,
                        error.message ?: resources.getString(R.string.start_failed),
                    )
                }
            return
        }
        runRecoverableCatching { VpnService.prepare(context) }.fold(
            onSuccess = { prepare ->
                if (prepare == null) {
                    requestVpnStart(plan)
                } else {
                    pendingConfig = plan
                    runRecoverableCatching { vpnLauncher.launch(prepare) }.onFailure { error ->
                        pendingConfig = null
                        failPendingVpnStart(
                            plan,
                            error.message ?: resources.getString(R.string.start_failed),
                        )
                    }
                }
            },
            onFailure = { error ->
                failPendingVpnStart(
                    plan,
                    error.message ?: resources.getString(R.string.start_failed),
                )
            },
        )
    }

    suspend fun applyRunningMutation(
        shouldApplyRuntime: (ProfilesState) -> Boolean = { true },
        transform: (ProfilesState) -> ProfilesState,
    ): Boolean {
        return try {
            val (mutationApplied, pendingInteractiveStart) = durableMutation(appContext, "profile runtime mutation") {
                profileMutationMutex.withLock profileMutation@{
                    var applyRuntime = false
                    var intendedOutboundUpdate = false
                    var plan: ProfileRunPlan? = null
                    val (previousState, committedSnapshot) = commitProfileMutationLocked(
                        transform = transform,
                        validate = { current, nextState ->
                            applyRuntime = shouldApplyRuntime(current)
                            intendedOutboundUpdate =
                                TcptunState.status == VpnStatus.Running && current.activeIds.isNotEmpty()
                            plan = if (!applyRuntime || nextState.activeIds.isEmpty()) {
                                null
                            } else {
                                withContext(Dispatchers.Default) { nextState.runPlan() }
                            }
                            plan?.let { candidatePlan ->
                                // Profile activation and route commits use the same lock order.
                                // Validate the exact generated Binder payload before activeIds
                                // can become authoritative, including first start from Stopped.
                                ProcessRouteRuleMutationMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        TcptunVpnService.preflightStartPayload(
                                            context = appContext,
                                            sourcePlan = candidatePlan,
                                            managedRouteRules = RouteRuleStore
                                                .loadAuthoritative(appContext)
                                                .getOrThrow(),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    if (!applyRuntime) return@profileMutation true to null
                    val committedPlan = plan
                    if (committedPlan == null) {
                        if (!stopVpn(appContext)) {
                            rollbackCommittedProfileMutation(committedSnapshot, previousState)
                            return@profileMutation false to null
                        }
                    } else {
                        TcptunState.clearTcping()
                        if (intendedOutboundUpdate) {
                            if (TcptunState.status == VpnStatus.Running) {
                                // Dispatch before releasing the mutation mutex. A later mutation may
                                // use this committed state as its rollback baseline only after the
                                // service has accepted the corresponding command.
                                val dispatched = updateVpnOutbounds(
                                    context = appContext,
                                    plan = committedPlan,
                                )
                                if (!dispatched) {
                                    rollbackCommittedProfileMutation(committedSnapshot, previousState)
                                    return@profileMutation false to null
                                }
                            } else {
                                TcptunState.appendLog(
                                    "profile runtime update skipped: VPN state changed to ${TcptunState.status}",
                                )
                            }
                        } else {
                            return@profileMutation true to committedPlan
                        }
                    }
                    true to null
                }
            }.await()
            if (!mutationApplied) return false
            pendingInteractiveStart?.let(::launchPlan)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiError(error.message ?: resources.getString(R.string.profile_save_failed))
            showLogs = true
            false
        }
    }

    fun toggleProfile(profile: AppConfig) {
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            applyRunningMutation { current ->
                val nextActiveIds = nextActiveProfileIds(
                    activeIds = current.activeIds,
                    profileId = profile.id,
                    vpnStatus = TcptunState.status,
                )
                current.copy(activeIds = nextActiveIds)
            }
        }
    }

    fun deleteProfile(profile: AppConfig) {
        if (isVpnTransitionStatus(vpnState.status)) return
        screenScope.launch {
            var profileIndex = -1
            var deletedProfile: AppConfig? = null
            var wasActive = false
            val deleteTransform: (ProfilesState) -> ProfilesState = { current ->
                profileIndex = current.profiles.indexOfFirst { it.id == profile.id }
                deletedProfile = current.profiles.getOrNull(profileIndex)
                current.copy(
                    profiles = current.profiles.filterNot { it.id == profile.id },
                    activeIds = current.activeIds - profile.id,
                )
            }
            val deleted = applyRunningMutation(
                shouldApplyRuntime = { current ->
                    (profile.id in current.activeIds).also { active -> wasActive = active }
                },
                transform = deleteTransform,
            )
            val removed = deletedProfile
            if (!deleted || removed == null || profileIndex < 0) return@launch
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showDismissibleSnackbar(
                message = resources.getString(R.string.profile_deleted, removed.name),
                actionLabel = undoLabel,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val restoreTransform: (ProfilesState) -> ProfilesState = { current ->
                    if (current.profiles.any { it.id == removed.id }) {
                        current
                    } else {
                        val restored = current.profiles.toMutableList()
                        restored.add(profileIndex.coerceIn(0, restored.size), removed)
                        current.copy(
                            profiles = restored,
                            activeIds = if (wasActive) current.activeIds + removed.id else current.activeIds,
                        )
                    }
                }
                applyRunningMutation(
                    shouldApplyRuntime = { wasActive },
                    transform = restoreTransform,
                )
            }
        }
    }

    fun startProfileDrag(profileId: String) {
        if (isVpnTransitionStatus(vpnState.status)) return
        draggedProfileId = profileId
        draggedProfileOffset = 0f
        profilesBeforeDrag = state.profiles
    }

    fun dragProfile(profileId: String, deltaY: Float) {
        if (draggedProfileId != profileId || !deltaY.isFinite()) return
        draggedProfileOffset += deltaY
        val visibleItems = profileListState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.firstOrNull { it.key == profileId } ?: return
        val draggedCenter = draggedItem.offset + draggedProfileOffset + draggedItem.size / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            item.key != profileId &&
                state.profiles.any { it.id == item.key } &&
                draggedCenter >= item.offset &&
                draggedCenter <= item.offset + item.size
        }
        if (targetItem != null) {
            val fromIndex = state.profiles.indexOfFirst { it.id == profileId }
            val toIndex = state.profiles.indexOfFirst { it.id == targetItem.key }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val reordered = state.profiles.toMutableList().also { profiles ->
                    profiles.add(toIndex, profiles.removeAt(fromIndex))
                }
                storedState = state.copy(profiles = reordered)
                draggedProfileOffset += draggedItem.offset - targetItem.offset
            }
        }

        if (profileReorderScrollJob?.isActive != true) {
            profileReorderScrollJob = screenScope.launch {
                while (draggedProfileId == profileId) {
                    val layoutInfo = profileListState.layoutInfo
                    val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == profileId } ?: break
                    val currentTop = currentItem.offset + draggedProfileOffset
                    val currentBottom = currentTop + currentItem.size
                    val scrollDelta = when {
                        currentTop < layoutInfo.viewportStartOffset -> -profileReorderScrollStep
                        currentBottom > layoutInfo.viewportEndOffset -> profileReorderScrollStep
                        else -> break
                    }
                    val consumed = profileListState.scrollBy(scrollDelta)
                    if (consumed == 0f) break
                    if (draggedProfileId == profileId) draggedProfileOffset += consumed
                    delay(16)
                }
            }
        }
    }

    fun finishProfileDrag() {
        val original = profilesBeforeDrag
        val reordered = state.profiles
        profileReorderScrollJob?.cancel()
        profileReorderScrollJob = null
        draggedProfileId = null
        draggedProfileOffset = 0f
        profilesBeforeDrag = null
        if (original != null && original.map { it.id } != reordered.map { it.id }) {
            val reorderedIds = reordered.map(AppConfig::id)
            screenScope.launch {
                val saved = saveProfileMutation { current ->
                    val byId = current.profiles.associateBy(AppConfig::id)
                    val ordered = buildList {
                        reorderedIds.mapNotNullTo(this) { byId[it] }
                        current.profiles.filterTo(this) { it.id !in reorderedIds }
                    }
                    current.copy(profiles = ordered)
                }
                if (saved == null) {
                    val current = storedState ?: return@launch
                    val byId = current.profiles.associateBy(AppConfig::id)
                    val originalIds = original.map(AppConfig::id)
                    storedState = current.copy(
                        profiles = buildList {
                            originalIds.mapNotNullTo(this) { byId[it] }
                            current.profiles.filterTo(this) { it.id !in originalIds }
                        },
                    )
                }
            }
        }
    }

    val editing = editingProfile
    val showingMainList = destination == MainDestination.Profiles && editing == null
    if (destination == MainDestination.QrScanner) {
        QrScannerPage(
            onBack = ::closeQrScanner,
            onProfileScanned = ::importScannedProfile,
        )
    } else if (destination == MainDestination.IpInformation) {
        IpInformationPage(onBack = { destination = MainDestination.Profiles })
    } else if (destination == MainDestination.Diagnostics) {
        DiagnosticsPage(
            onBack = { destination = MainDestination.Profiles },
            onShowLogs = { showLogs = true },
        )
    } else if (destination == MainDestination.Settings) {
        SettingsPage(
            onBack = { destination = MainDestination.Profiles },
        )
    } else if (destination == MainDestination.FlowAnalysis) {
        FlowAnalysisPage(onBack = { destination = MainDestination.Profiles })
    } else if (destination == MainDestination.RouteManagement) {
        RouteManagementPage(
            onBack = { destination = MainDestination.Profiles },
        )
    } else if (editing == null) {
        val listIpInfo = rememberLocalIpInfo(context)
        val configuredListenAddress = RuntimeSettingsRepository.localSocksListenAddress(
            rememberUiRuntimeSettings(appContext) ?: RuntimeSettings(),
        )
        val effectiveListenAddress = vpnState.diagnostics.bridgeListen
            .takeIf { vpnState.status == VpnStatus.Running }
            .orEmpty()
            .ifBlank { configuredListenAddress }
        val proxyAccess = proxyAccessDisplay(
            listenAddress = effectiveListenAddress,
            hotspotIpv4 = listIpInfo.info.hotspotIpv4,
            underlyingIpv4 = listIpInfo.info.underlyingIpv4,
            proxyRunning = vpnState.status == VpnStatus.Running,
        )
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
            TopBar(
                title = stringResource(R.string.profiles_title),
                actionsEnabled = !isVpnTransitionStatus(vpnState.status),
                onDiagnostics = { destination = MainDestination.Diagnostics },
                onRouteManagement = { destination = MainDestination.RouteManagement },
                onFlowAnalysis = { destination = MainDestination.FlowAnalysis },
                onSettings = { destination = MainDestination.Settings },
                onImport = ::importFromClipboard,
                onScan = ::openQrScanner,
            )
            },
            snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
            bottomBar = {
                val tcpingEnabled = canStartTcping(
                    status = vpnState.status,
                    activeProfileCount = state.activeProfiles.size,
                    connectionsReady = vpnState.connectionsReady,
                )
                BottomStatus(
                    status = vpnState.status,
                    error = vpnState.lastError,
                    tcping = vpnState.tcping,
                    hasProfiles = state.profiles.isNotEmpty(),
                    connectionsReady = vpnState.connectionsReady,
                    tcpingEnabled = tcpingEnabled,
                    onClick = {
                        if (!tcpingEnabled || vpnState.tcping.running) return@BottomStatus
                        val tcpingTarget = TCPING_TARGETS.getOrNull(tcpingTargetIndex)
                            ?: TCPING_TARGETS.firstOrNull()
                            ?: return@BottomStatus
                        tcpingTargetIndex = (tcpingTargetIndex + 1) % TCPING_TARGETS.size
                        val requestId = TcptunState.beginTcping(
                            targetLabel = tcpingTarget.label,
                            total = state.activeProfiles.size,
                        )
                        runRecoverableCatching {
                            context.startService(
                                TcptunVpnService.tcpingOutboundsIntent(
                                    context = context,
                                    requestId = requestId,
                                    targetLabel = tcpingTarget.label,
                                    host = tcpingTarget.host,
                                    port = tcpingTarget.port,
                                ),
                            )
                        }.onFailure { err ->
                            TcptunState.failTcping(requestId, err.message ?: resources.getString(R.string.tcping_failed_fallback))
                        }
                    },
                )
            },
        ) { padding ->
            PullRefreshContainer(
                onRefresh = {
                    val generation = profileReloadGeneration.incrementAndGet()
                    val loaded = profileMutationMutex.withLock {
                        withContext(Dispatchers.IO) { profileRepository.load(appContext) }
                    }
                    if (generation == profileReloadGeneration.get()) storedState = loaded
                    listIpInfo.refresh()
                    refreshRunningDiagnostics()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
            LazyColumn(
                state = profileListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
            ) {
                item(key = "mixed-listener-network-header") {
                    ProfileListHeader(
                        proxyAccess = proxyAccess,
                        onIpClick = { destination = MainDestination.IpInformation },
                    )
                }
                items(state.profiles, key = { it.id }) { profile ->
                    val dragging = draggedProfileId == profile.id
                    ProfileRow(
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) draggedProfileOffset else 0f
                                shadowElevation = if (dragging) 8.dp.toPx() else 0f
                                shape = CardShape
                            },
                        profile = profile,
                        running = profile.id in state.activeIds && isVpnActiveStatus(vpnState.status),
                        health = vpnState.profileHealth[profile.id],
                        enabled = !isVpnTransitionStatus(vpnState.status),
                        onClick = { toggleProfile(profile) },
                        onShare = { uri -> shareProfile(context, uri) },
                        onShowQrCode = { profileQrCode = profile },
                        onEdit = { editingProfile = profile },
                        dragging = dragging,
                        onDragStart = { startProfileDrag(profile.id) },
                        onDrag = { deltaY -> dragProfile(profile.id, deltaY) },
                        onDragEnd = ::finishProfileDrag,
                        onDeleteRequest = { deleteProfile(profile) },
                    )
                }
                if (state.profiles.isEmpty()) {
                    item {
                        EmptyState(
                            enabled = !isVpnTransitionStatus(vpnState.status),
                            onAdd = {
                                editingProfile = AppConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = "proxy",
                                )
                            },
                        )
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
                screenScope.launch {
                    val transform: (ProfilesState) -> ProfilesState = { current ->
                        val profiles = current.profiles.toMutableList()
                        val index = profiles.indexOfFirst { it.id == updated.id }
                        if (index >= 0) profiles[index] = updated else profiles.add(updated)
                        current.copy(profiles = profiles)
                    }
                    val saved = applyRunningMutation(
                        shouldApplyRuntime = { current ->
                            updated.id in current.activeIds && TcptunState.status == VpnStatus.Running
                        },
                        transform = transform,
                    )
                    if (saved) editingProfile = null
                }
            },
        )
    }

    // Sub-pages replace the main Scaffold, so keep the process-wide error host
    // visible there as an overlay as well.
    if (!showingMainList) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AutoDismissSnackbarHost(snackbarHostState)
        }
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
            enabled = !isVpnTransitionStatus(vpnState.status),
            onConfirm = {
                pendingDeepLinkProfile = null
                screenScope.launch {
                    try {
                        val (storedProfile, added) = storeValidatedProfile(profile)
                            val message = if (added) {
                                resources.getString(R.string.profile_imported, storedProfile.name)
                            } else {
                                resources.getString(R.string.profile_already_exists, storedProfile.name)
                            }
                        snackbarHostState.showDismissibleSnackbar(message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        snackbarHostState.showDismissibleSnackbar(
                            resources.getString(R.string.invalid_profile_link),
                        )
                    }
                }
            },
            onDismiss = { pendingDeepLinkProfile = null },
        )
    }

}
