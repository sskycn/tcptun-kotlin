@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import android.view.MotionEvent
import java.io.File
import org.opencv.core.CvType
import org.opencv.core.Rect as CvRect
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size as CvSize
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.wechat_qrcode.WeChatQRCode
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

private enum class CameraPermissionState {
    Requesting,
    Granted,
    Denied,
}

@Composable
internal fun QrScannerPage(
    onBack: () -> Unit,
    onProfileScanned: (String) -> Boolean,
) {
    val context = LocalContext.current
    var permissionState by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                CameraPermissionState.Granted
            } else {
                CameraPermissionState.Requesting
            },
        )
    }
    var engineReady by remember { mutableStateOf(false) }
    var engineFailed by remember { mutableStateOf(false) }
    var engineAttempt by remember { mutableIntStateOf(0) }
    var cameraBindAttempt by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf(false) }
    var scannerRuntimeError by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var invalidProfileCode by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionState = if (granted) CameraPermissionState.Granted else CameraPermissionState.Denied
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(permissionState) {
        if (permissionState == CameraPermissionState.Requesting) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(engineAttempt) {
        engineReady = false
        engineFailed = false
        val result = withContext(Dispatchers.Default) {
            WeChatQrEngine.initialize(context.applicationContext)
        }
        engineReady = result.isSuccess
        engineFailed = result.isFailure
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.scan_profile_qr_code),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                permissionState == CameraPermissionState.Requesting -> {
                    ScannerLoading(modifier = Modifier.align(Alignment.Center))
                }

                permissionState == CameraPermissionState.Denied -> {
                    ScannerMessage(
                        message = stringResource(R.string.camera_permission_required),
                        buttonLabel = stringResource(R.string.grant_camera_permission),
                        onClick = { permissionState = CameraPermissionState.Requesting },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                engineFailed -> {
                    ScannerMessage(
                        message = stringResource(R.string.scanner_initialization_failed),
                        buttonLabel = stringResource(R.string.scanner_retry),
                        onClick = { engineAttempt += 1 },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                !engineReady -> {
                    ScannerLoading(modifier = Modifier.align(Alignment.Center))
                }

                cameraError -> {
                    ScannerMessage(
                        message = stringResource(R.string.camera_start_failed),
                        buttonLabel = stringResource(R.string.scanner_retry),
                        onClick = {
                            cameraError = false
                            cameraBindAttempt += 1
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                scannerRuntimeError -> {
                    ScannerMessage(
                        message = stringResource(R.string.scanner_initialization_failed),
                        buttonLabel = stringResource(R.string.scanner_retry),
                        onClick = {
                            scannerRuntimeError = false
                            cameraBindAttempt += 1
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    QrCameraPreview(
                        bindingKey = cameraBindAttempt,
                        onCameraReady = {
                            camera = it
                            if (it == null) torchEnabled = false
                        },
                        onCameraError = { cameraError = true },
                        onScannerError = { scannerRuntimeError = true },
                        onCodeDetected = { code ->
                            val accepted = onProfileScanned(code)
                            invalidProfileCode = !accepted
                            accepted
                        },
                    )
                    ScannerFrameOverlay(
                        modifier = Modifier.fillMaxSize(),
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.scan_qr_instruction),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (invalidProfileCode) {
                                    Text(
                                        text = stringResource(R.string.invalid_scanned_profile),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                                FilledTonalButton(
                                    onClick = {
                                        val next = !torchEnabled
                                        camera?.cameraControl?.enableTorch(next)
                                        torchEnabled = next
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (torchEnabled) Icons.Rounded.FlashlightOff else Icons.Rounded.FlashlightOn,
                                        contentDescription = stringResource(
                                            if (torchEnabled) R.string.turn_flashlight_off else R.string.turn_flashlight_on,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.scan_qr_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScannerMessage(
    message: String,
    buttonLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.QrCodeScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = onClick) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun ScannerFrameOverlay(modifier: Modifier = Modifier) {
    val frameColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val side = min(size.width, size.height) * 0.72f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val scrim = Color.Black.copy(alpha = 0.5f)

        drawRect(scrim, size = ComposeSize(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = ComposeSize(size.width, size.height - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = ComposeSize(left, side))
        drawRect(scrim, topLeft = Offset(right, top), size = ComposeSize(size.width - right, side))
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = ComposeSize(side, side),
            cornerRadius = CornerRadius(24.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

/** Two-stage scan target: low-resolution CNN locate → camera AF/zoom → cropped SR decode. */
internal data class DynamicFocusTarget(
    val normalizedX: Float,
    val normalizedY: Float,
    /** Largest side of the QR box relative to min(frame width, height). */
    val sizeRatio: Float,
    val updatedAtMs: Long,
)

internal data class FrameScanResult(
    val text: String? = null,
    val focus: DynamicFocusTarget? = null,
)

@Composable
private fun QrCameraPreview(
    bindingKey: Int,
    onCameraReady: (Camera?) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onScannerError: (Throwable) -> Unit,
    onCodeDetected: (String) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnScannerError by rememberUpdatedState(onScannerError)
    val currentOnCodeDetected by rememberUpdatedState(onCodeDetected)
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val lastDecodeAt = remember { AtomicLong(0L) }
    val focusTarget = remember { AtomicReference<DynamicFocusTarget?>(null) }
    val decodeArmed = remember { AtomicBoolean(false) }
    val manualFocusUntil = remember { AtomicLong(0L) }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    // Keep detection running while the lens moves. Only decoding is gated, so a moving QR code
    // can continuously refresh the AF target instead of leaving the camera with stale coordinates.
    LaunchedEffect(boundCamera, bindingKey) {
        val camera = boundCamera ?: return@LaunchedEffect
        val control = camera.cameraControl
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        var smoothedTarget: DynamicFocusTarget? = null
        var lastFocusedTarget: DynamicFocusTarget? = null
        var lastFocusAt = 0L
        var lastZoomAt = 0L
        var lastTargetAt = SystemClock.elapsedRealtime()
        var manualFocusWasActive = false

        fun armDecodeIfManualFocusIsIdle(ready: Boolean = true) {
            decodeArmed.set(
                ready && SystemClock.elapsedRealtime() >= manualFocusUntil.get(),
            )
        }

        suspend fun applyZoomRatio(requested: Float, force: Boolean = false): Boolean {
            val state = camera.cameraInfo.zoomState.value ?: return false
            val target = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
            if (!force && relativeDifference(state.zoomRatio, target) < ZOOM_CHANGE_THRESHOLD) return false
            decodeArmed.set(false)
            try {
                control.setZoomRatio(target).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return false
            }
            delay(ZOOM_SETTLE_MS)
            return true
        }

        suspend fun focusUntilSettled(point: MeteringPoint): Boolean {
            decodeArmed.set(false)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF,
            )
                .setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS)
                .build()
            return try {
                val focused = control.startFocusAndMetering(action).await().isFocusSuccessful
                delay(if (focused) AF_POST_LOCK_MS else AF_POST_LOCK_MS / 2)
                focused
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
        }

        val initialZoomState = camera.cameraInfo.zoomState.value
        val baseZoom = 1f.coerceIn(
            initialZoomState?.minZoomRatio ?: 1f,
            initialZoomState?.maxZoomRatio ?: 1f,
        )
        applyZoomRatio(baseZoom, force = true)
        focusUntilSettled(factory.createPoint(0.5f, 0.5f))
        armDecodeIfManualFocusIsIdle()
        lastFocusAt = SystemClock.elapsedRealtime()

        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now < manualFocusUntil.get()) {
                manualFocusWasActive = true
                delay(FOCUS_TRACK_INTERVAL_MS)
                continue
            }
            if (manualFocusWasActive) {
                manualFocusWasActive = false
                lastFocusedTarget = focusTarget.get()
                lastFocusAt = now
            }

            val observedTarget = focusTarget.get()?.takeIf { now - it.updatedAtMs <= FOCUS_TARGET_TTL_MS }
            if (observedTarget != null) {
                lastTargetAt = now
                smoothedTarget = smoothTarget(smoothedTarget, observedTarget)
                val target = requireNotNull(smoothedTarget)
                val zoomState = camera.cameraInfo.zoomState.value
                val currentZoom = zoomState?.zoomRatio ?: baseZoom
                val maxTrackingZoom = min(
                    zoomState?.maxZoomRatio ?: baseZoom,
                    baseZoom * ZOOM_TRACK_MAX_MULTIPLIER,
                )
                val desiredZoom = desiredZoomRatio(
                    currentZoom = currentZoom,
                    observedSizeRatio = target.sizeRatio,
                    minZoom = baseZoom,
                    maxZoom = maxTrackingZoom,
                )
                val zoomed = if (now - lastZoomAt >= ZOOM_CHANGE_COOLDOWN_MS) {
                    applyZoomRatio(desiredZoom).also { changed ->
                        if (changed) lastZoomAt = SystemClock.elapsedRealtime()
                    }
                } else {
                    false
                }

                val shouldRefocus = zoomed ||
                    lastFocusedTarget == null ||
                    targetMoved(lastFocusedTarget, target) ||
                    now - lastFocusAt >= FOCUS_REFRESH_INTERVAL_MS
                if (shouldRefocus) {
                    val targetBeforeFocus = focusTarget.get() ?: target
                    focusUntilSettled(
                        factory.createPoint(
                            target.normalizedX.coerceIn(0.05f, 0.95f),
                            target.normalizedY.coerceIn(0.05f, 0.95f),
                        ),
                    )
                    val settledAt = SystemClock.elapsedRealtime()
                    val latestTarget = focusTarget.get()?.takeIf {
                        settledAt - it.updatedAtMs <= FOCUS_TARGET_TTL_MS
                    }
                    // A large target move during AF means that lock is already stale. Keep
                    // tracking immediately; otherwise allow the sharp frame to decode.
                    armDecodeIfManualFocusIsIdle(
                        latestTarget == null || !targetMoved(targetBeforeFocus, latestTarget),
                    )
                    lastFocusedTarget = latestTarget ?: target
                    lastFocusAt = settledAt
                } else {
                    armDecodeIfManualFocusIsIdle()
                }
            } else {
                smoothedTarget = null
                lastFocusedTarget = null
                val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: baseZoom
                val shouldRestoreFieldOfView = now - lastTargetAt >= TARGET_LOST_ZOOM_OUT_MS &&
                    relativeDifference(currentZoom, baseZoom) >= ZOOM_CHANGE_THRESHOLD
                if (shouldRestoreFieldOfView) {
                    if (applyZoomRatio(baseZoom)) lastZoomAt = SystemClock.elapsedRealtime()
                    focusUntilSettled(factory.createPoint(0.5f, 0.5f))
                    lastFocusAt = SystemClock.elapsedRealtime()
                } else if (now - lastFocusAt >= CENTER_FOCUS_REFRESH_INTERVAL_MS) {
                    focusUntilSettled(factory.createPoint(0.5f, 0.5f))
                    lastFocusAt = SystemClock.elapsedRealtime()
                }
                armDecodeIfManualFocusIsIdle()
            }
            delay(FOCUS_TRACK_INTERVAL_MS)
        }
    }

    DisposableEffect(context, lifecycleOwner, previewView, bindingKey) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        val analyzer = WeChatQrAnalyzer(
            isDecodeArmed = {
                decodeArmed.get() && SystemClock.elapsedRealtime() - lastDecodeAt.get() >= DECODE_COOLDOWN_MS
            },
            onFocusTarget = { focusTarget.set(it) },
            onCodeDetected = { code, resume ->
                lastDecodeAt.set(SystemClock.elapsedRealtime())
                mainExecutor.execute {
                    if (!disposed && !currentOnCodeDetected(code)) resume()
                }
            },
            onError = { error ->
                mainExecutor.execute {
                    if (!disposed) currentOnScannerError(error)
                }
            },
        )
        var cameraProvider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener(
            {
                if (disposed) return@addListener
                try {
                    val provider = providerFuture.get()
                    val analysisResolution = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setOutputImageRotationEnabled(true)
                        .setResolutionSelector(analysisResolution)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    cameraProvider = provider
                    previewUseCase = preview
                    analysisUseCase = analysis
                    previewView.setOnTouchListener { view, event ->
                        if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        )
                            .setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS)
                            .build()
                        decodeArmed.set(false)
                        manualFocusUntil.set(SystemClock.elapsedRealtime() + MANUAL_FOCUS_PRIORITY_MS)
                        runCatching {
                            camera.cameraControl.startFocusAndMetering(action).addListener(
                                {
                                    if (!disposed) decodeArmed.set(true)
                                },
                                mainExecutor,
                            )
                        }.onFailure {
                            decodeArmed.set(true)
                        }
                        view.performClick()
                        true
                    }
                    if (!disposed) {
                        boundCamera = camera
                        currentOnCameraReady(camera)
                    }
                } catch (error: Throwable) {
                    if (!disposed) currentOnCameraError(error)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            boundCamera = null
            focusTarget.set(null)
            analyzer.close()
            previewView.setOnTouchListener(null)
            analysisUseCase?.clearAnalyzer()
            cameraProvider?.let { provider ->
                previewUseCase?.let { provider.unbind(it) }
                analysisUseCase?.let { provider.unbind(it) }
            }
            analysisExecutor.shutdownNow()
            currentOnCameraReady(null)
        }
    }
}

private class WeChatQrAnalyzer(
    private val isDecodeArmed: () -> Boolean,
    private val onFocusTarget: (DynamicFocusTarget) -> Unit,
    private val onCodeDetected: (String, resume: () -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val awaitingResult = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastAnalysisAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        // Detection remains active during AF/zoom so the tracking target stays current. Only
        // delivery of decoded text is gated until the lens has settled.
        if (closed.get() ||
            awaitingResult.get() ||
            now - lastAnalysisAt < ANALYSIS_INTERVAL_MS
        ) {
            image.close()
            return
        }
        lastAnalysisAt = now
        try {
            val decodeThisFrame = isDecodeArmed()
            val gray = image.toGrayscaleMat()
            val result = try {
                scanFrame(gray, decode = decodeThisFrame)
            } finally {
                gray.release()
            }
            result.focus?.let { onFocusTarget(it) }
            val code = result.text
            if (!closed.get() &&
                decodeThisFrame &&
                code != null &&
                awaitingResult.compareAndSet(false, true)
            ) {
                onCodeDetected(code) { awaitingResult.set(false) }
            }
            errorReported.set(false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed.get() && errorReported.compareAndSet(false, true)) onError(error)
        } finally {
            image.close()
        }
    }

    fun close() {
        closed.set(true)
        awaitingResult.set(true)
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 120L
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(false) }
        addListener(
            {
                if (!continuation.isActive) return@addListener
                try {
                    continuation.resume(get())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            },
            { runnable -> runnable.run() },
        )
    }

private fun ImageProxy.toGrayscaleMat(): Mat {
    val yPlane = planes.firstOrNull() ?: error("Camera frame has no luminance plane")
    val buffer = yPlane.buffer.duplicate()
    buffer.rewind()
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    val pixels = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(pixels, 0, min(pixels.size, buffer.remaining()))
    } else {
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (pixelStride == 1) {
                buffer.position(rowStart)
                buffer.get(pixels, row * width, width)
            } else {
                for (column in 0 until width) {
                    pixels[row * width + column] = buffer.get(rowStart + column * pixelStride)
                }
            }
        }
    }
    return Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, pixels) }
}

private fun scanFrame(source: Mat, decode: Boolean): FrameScanResult =
    WeChatQrEngine.locateAndDecode(source, decode)

internal data class QrLocatorCandidate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal object WeChatQrEngine {
    private val lock = Any()
    private val inferenceLock = Any()

    private lateinit var locator: Net
    private lateinit var decoder: WeChatQRCode
    private var lastFullFrameFallbackAt = 0L

    @Volatile
    private var initialized = false

    fun initialize(context: Context): Result<Unit> = runCatching {
        if (initialized) return@runCatching
        synchronized(lock) {
            if (!initialized) {
                OpenCvRuntime.ensureInitialized()
                val modelDirectory = prepareModelDirectory(context.applicationContext)
                locator = Dnn.readNetFromCaffe(
                    File(modelDirectory, DETECT_PROTO_FILE).absolutePath,
                    File(modelDirectory, DETECT_MODEL_FILE).absolutePath,
                )
                check(!locator.empty()) { "QR locator model is empty" }
                // Keep the detector enabled inside candidate crops. The decoder-only constructor
                // is not behaviorally equivalent on all Android/OpenCV builds.
                decoder = WeChatQRCode(
                    File(modelDirectory, DETECT_PROTO_FILE).absolutePath,
                    File(modelDirectory, DETECT_MODEL_FILE).absolutePath,
                    File(modelDirectory, SR_PROTO_FILE).absolutePath,
                    File(modelDirectory, SR_MODEL_FILE).absolutePath,
                )
                initialized = true
            }
        }
    }

    fun locateAndDecode(gray: Mat, decode: Boolean): FrameScanResult {
        check(initialized) { "QR code scanner is not initialized" }
        return synchronized(inferenceLock) {
            val candidates = locate(gray)
            val ranked = rankCandidates(candidates, gray.cols(), gray.rows())
            val focus = ranked.firstOrNull()?.toFocusTarget(gray.cols(), gray.rows())
            if (!decode) return@synchronized FrameScanResult(focus = focus)

            for (candidate in ranked.take(MAX_DECODE_CANDIDATES)) {
                val cropBounds = candidate.toPaddedCrop(gray.cols(), gray.rows()) ?: continue
                val crop = gray.submat(cropBounds)
                val text = try {
                    decoder.detectAndDecode(crop).firstOrNull { it.isNotBlank() }
                } finally {
                    crop.release()
                }
                if (text != null) return@synchronized FrameScanResult(text = text, focus = focus)
            }

            // Compatibility safety net: candidate location is an optimization, never a
            // prerequisite for scanning. Run the proven full WeChat pipeline at a low cadence
            // when candidate decoding produced no result.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFullFrameFallbackAt >= FULL_FRAME_FALLBACK_INTERVAL_MS) {
                lastFullFrameFallbackAt = now
                val text = decoder.detectAndDecode(gray).firstOrNull { it.isNotBlank() }
                if (text != null) return@synchronized FrameScanResult(text = text, focus = focus)
            }
            FrameScanResult(focus = focus)
        }
    }

    private fun locate(gray: Mat): List<QrLocatorCandidate> {
        val scale = min(1f, sqrt(LOCATOR_TARGET_AREA / (gray.cols() * gray.rows()).toFloat()))
        val targetWidth = max(1, (gray.cols() * scale).roundToInt())
        val targetHeight = max(1, (gray.rows() * scale).roundToInt())
        val resized = Mat()
        val blob: Mat
        val output: Mat
        try {
            Imgproc.resize(
                gray,
                resized,
                CvSize(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC,
            )
            blob = Dnn.blobFromImage(
                resized,
                1.0 / 255.0,
                CvSize(targetWidth.toDouble(), targetHeight.toDouble()),
                Scalar(0.0, 0.0, 0.0),
                false,
                false,
            )
        } finally {
            resized.release()
        }
        try {
            locator.setInput(blob, LOCATOR_INPUT_NAME)
            output = locator.forward(LOCATOR_OUTPUT_NAME)
        } finally {
            blob.release()
        }
        return try {
            val rows = if (output.dims() >= 3) output.size(2) else 0
            val values = FloatArray(LOCATOR_OUTPUT_COLUMNS)
            buildList(rows) {
                for (row in 0 until rows) {
                    if (output.get(intArrayOf(0, 0, row, 0), values) == values.size) {
                        add(values.copyOf())
                    }
                }
            }.let { parseLocatorOutput(it, gray.cols(), gray.rows()) }
        } finally {
            output.release()
        }
    }

    private fun prepareModelDirectory(context: Context): File {
        val directory = File(context.noBackupFilesDir, MODEL_DIRECTORY).apply {
            check(exists() || mkdirs()) { "Unable to create QR model directory" }
        }
        for (fileName in MODEL_FILES) {
            val target = File(directory, fileName)
            if (!target.isFile || target.length() == 0L) {
                context.assets.open("models/$fileName").use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
        return directory
    }
}

internal fun parseLocatorOutput(
    rows: List<FloatArray>,
    frameWidth: Int,
    frameHeight: Int,
): List<QrLocatorCandidate> = buildList {
    for (row in rows) {
        if (row.size < LOCATOR_OUTPUT_COLUMNS) continue
        val label = row[1].roundToInt()
        val confidence = row[2]
        if (label != QR_LOCATOR_LABEL || !confidence.isFinite() || confidence <= LOCATOR_MIN_CONFIDENCE) continue
        val coordinates = row.sliceArray(3..6)
        if (coordinates.any { !it.isFinite() }) continue
        val x0 = (min(coordinates[0], coordinates[2]) * frameWidth).coerceIn(0f, frameWidth - 1f)
        val y0 = (min(coordinates[1], coordinates[3]) * frameHeight).coerceIn(0f, frameHeight - 1f)
        val x1 = (max(coordinates[0], coordinates[2]) * frameWidth).coerceIn(0f, frameWidth - 1f)
        val y1 = (max(coordinates[1], coordinates[3]) * frameHeight).coerceIn(0f, frameHeight - 1f)
        if (x1 - x0 < MIN_LOCATOR_BOX_SIDE || y1 - y0 < MIN_LOCATOR_BOX_SIDE) continue
        add(QrLocatorCandidate(x0, y0, x1, y1, confidence))
    }
}

private fun rankCandidates(
    candidates: List<QrLocatorCandidate>,
    frameWidth: Int,
    frameHeight: Int,
): List<QrLocatorCandidate> = candidates.sortedByDescending { candidate ->
    val normalizedDistance = hypot(
        ((candidate.centerX - frameWidth / 2f) / frameWidth).toDouble(),
        ((candidate.centerY - frameHeight / 2f) / frameHeight).toDouble(),
    ).toFloat()
    max(candidate.width, candidate.height) *
        candidate.confidence *
        (1f - normalizedDistance.coerceAtMost(0.75f))
}

private fun QrLocatorCandidate.toFocusTarget(frameWidth: Int, frameHeight: Int) =
    DynamicFocusTarget(
        normalizedX = (centerX / frameWidth).coerceIn(0f, 1f),
        normalizedY = (centerY / frameHeight).coerceIn(0f, 1f),
        sizeRatio = (max(width, height) / min(frameWidth, frameHeight)).coerceIn(0f, 1f),
        updatedAtMs = SystemClock.elapsedRealtime(),
    )

private fun QrLocatorCandidate.toPaddedCrop(frameWidth: Int, frameHeight: Int): CvRect? {
    val horizontalPadding = max(width * CANDIDATE_PADDING_RATIO, MIN_CANDIDATE_PADDING_PX)
    val verticalPadding = max(height * CANDIDATE_PADDING_RATIO, MIN_CANDIDATE_PADDING_PX)
    val cropLeft = (left - horizontalPadding).coerceAtLeast(0f).toInt()
    val cropTop = (top - verticalPadding).coerceAtLeast(0f).toInt()
    val cropRight = (right + horizontalPadding).coerceAtMost(frameWidth.toFloat()).roundToInt()
    val cropBottom = (bottom + verticalPadding).coerceAtMost(frameHeight.toFloat()).roundToInt()
    val cropWidth = cropRight - cropLeft
    val cropHeight = cropBottom - cropTop
    return if (cropWidth >= MIN_DECODE_CROP_SIDE && cropHeight >= MIN_DECODE_CROP_SIDE) {
        CvRect(cropLeft, cropTop, cropWidth, cropHeight)
    } else {
        null
    }
}

internal fun smoothTarget(
    previous: DynamicFocusTarget?,
    observed: DynamicFocusTarget,
): DynamicFocusTarget {
    if (previous == null || targetDistance(previous, observed) >= TARGET_SMOOTHING_RESET_DISTANCE) {
        return observed
    }
    return observed.copy(
        normalizedX = previous.normalizedX +
            (observed.normalizedX - previous.normalizedX) * TARGET_POSITION_SMOOTHING,
        normalizedY = previous.normalizedY +
            (observed.normalizedY - previous.normalizedY) * TARGET_POSITION_SMOOTHING,
        sizeRatio = previous.sizeRatio +
            (observed.sizeRatio - previous.sizeRatio) * TARGET_SIZE_SMOOTHING,
    )
}

private fun targetDistance(first: DynamicFocusTarget?, second: DynamicFocusTarget): Float {
    if (first == null) return Float.POSITIVE_INFINITY
    return hypot(
        (first.normalizedX - second.normalizedX).toDouble(),
        (first.normalizedY - second.normalizedY).toDouble(),
    ).toFloat()
}

internal fun targetMoved(first: DynamicFocusTarget?, second: DynamicFocusTarget): Boolean {
    if (first == null) return true
    val sizeChange = relativeDifference(first.sizeRatio, second.sizeRatio)
    return targetDistance(first, second) >= TARGET_REFOCUS_DISTANCE ||
        sizeChange >= TARGET_REFOCUS_SIZE_CHANGE
}

/**
 * Preserve zoom once a target reaches a useful size. Computing the next ratio from both the
 * current ratio and observed box size avoids the 1x ↔ zoomed feedback loop of fixed thresholds.
 */
internal fun desiredZoomRatio(
    currentZoom: Float,
    observedSizeRatio: Float,
    minZoom: Float,
    maxZoom: Float,
): Float {
    if (observedSizeRatio in TARGET_SIZE_LOWER_BOUND..TARGET_SIZE_UPPER_BOUND) return currentZoom
    val safeSize = observedSizeRatio.coerceAtLeast(MIN_TARGET_SIZE_RATIO)
    return (currentZoom * TARGET_SIZE_RATIO / safeSize).coerceIn(minZoom, maxZoom)
}

private fun relativeDifference(first: Float, second: Float): Float {
    val denominator = max(abs(first), abs(second)).coerceAtLeast(0.001f)
    return abs(first - second) / denominator
}

/** Tracking tick; AF itself is event-driven and is not restarted on every tick. */
private const val FOCUS_TRACK_INTERVAL_MS = 160L

/** How long a detector box stays valid for AF/zoom guidance. */
private const val FOCUS_TARGET_TTL_MS = 900L

/** Pause search shortly after a successful decode. */
private const val DECODE_COOLDOWN_MS = 700L

/** After zoom finishes, wait for the stream before AF. */
private const val ZOOM_SETTLE_MS = 260L

/** After AF reports done, wait so the preview is optically sharp. */
private const val AF_POST_LOCK_MS = 140L

private const val FOCUS_REFRESH_INTERVAL_MS = 2600L
private const val CENTER_FOCUS_REFRESH_INTERVAL_MS = 3000L
private const val TARGET_LOST_ZOOM_OUT_MS = 700L
private const val ZOOM_CHANGE_COOLDOWN_MS = 700L
private const val MANUAL_FOCUS_PRIORITY_MS = 1800L

private const val TARGET_REFOCUS_DISTANCE = 0.065f
private const val TARGET_REFOCUS_SIZE_CHANGE = 0.20f
private const val TARGET_SMOOTHING_RESET_DISTANCE = 0.30f
private const val TARGET_POSITION_SMOOTHING = 0.48f
private const val TARGET_SIZE_SMOOTHING = 0.35f

private const val TARGET_SIZE_RATIO = 0.42f
private const val TARGET_SIZE_LOWER_BOUND = 0.30f
private const val TARGET_SIZE_UPPER_BOUND = 0.58f
private const val MIN_TARGET_SIZE_RATIO = 0.03f
private const val ZOOM_CHANGE_THRESHOLD = 0.12f
private const val ZOOM_TRACK_MAX_MULTIPLIER = 4f

private const val MODEL_DIRECTORY = "wechat_qrcode_models"
private const val DETECT_PROTO_FILE = "detect.prototxt"
private const val DETECT_MODEL_FILE = "detect.caffemodel"
private const val SR_PROTO_FILE = "sr.prototxt"
private const val SR_MODEL_FILE = "sr.caffemodel"
private val MODEL_FILES = listOf(DETECT_PROTO_FILE, DETECT_MODEL_FILE, SR_PROTO_FILE, SR_MODEL_FILE)

private const val LOCATOR_INPUT_NAME = "data"
private const val LOCATOR_OUTPUT_NAME = "detection_output"
private const val LOCATOR_TARGET_AREA = 160_000f
private const val LOCATOR_OUTPUT_COLUMNS = 7
private const val QR_LOCATOR_LABEL = 1
private const val LOCATOR_MIN_CONFIDENCE = 0.00001f
private const val MIN_LOCATOR_BOX_SIDE = 6f
private const val MAX_DECODE_CANDIDATES = 3
private const val FULL_FRAME_FALLBACK_INTERVAL_MS = 350L
private const val CANDIDATE_PADDING_RATIO = 0.10f
private const val MIN_CANDIDATE_PADDING_PX = 15f
private const val MIN_DECODE_CROP_SIDE = 21

private const val FOCUS_HOLD_SECONDS = 2L
