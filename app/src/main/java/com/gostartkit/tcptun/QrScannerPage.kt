package com.tcptun.client

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private enum class CameraPermissionState {
    Requesting,
    Granted,
    Denied,
}

internal const val QrCameraReadyTestTag = "qr-camera-ready"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrScannerPage(
    onBack: () -> Unit,
    onProfileScanned: (String, onComplete: (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var permissionState by remember {
        mutableStateOf(
            if (runRecoverableCatching {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
            ) {
                CameraPermissionState.Granted
            } else {
                CameraPermissionState.Requesting
            },
        )
    }
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
            runRecoverableCatching { permissionLauncher.launch(Manifest.permission.CAMERA) }
                .onFailure { permissionState = CameraPermissionState.Denied }
        }
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
                        onCodeDetected = { code, onComplete ->
                            onProfileScanned(code) { accepted ->
                                invalidProfileCode = !accepted
                                onComplete(accepted)
                            }
                        },
                    )
                    ScannerFrameOverlay(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (camera != null) Modifier.testTag(QrCameraReadyTestTag) else Modifier),
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
                            val activeCamera = camera
                            val hasFlash = runRecoverableCatching { activeCamera?.cameraInfo?.hasFlashUnit() == true }
                                .getOrDefault(false)
                            if (activeCamera != null && hasFlash) {
                                FilledTonalButton(
                                    onClick = {
                                        val next = !torchEnabled
                                        runRecoverableCatching { activeCamera.cameraControl.enableTorch(next) }.fold(
                                            onSuccess = { torchEnabled = next },
                                            onFailure = { scannerRuntimeError = true },
                                        )
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

        drawRect(scrim, size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, side))
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(24.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

@Composable
private fun QrCameraPreview(
    bindingKey: Int,
    onCameraReady: (Camera?) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onScannerError: (Throwable) -> Unit,
    onCodeDetected: (String, onComplete: (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewViewResult = remember(context) {
        runRecoverableCatching {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    }
    val previewView = previewViewResult.getOrNull()
    if (previewView == null) {
        val error = previewViewResult.exceptionOrNull()
            ?: IllegalStateException("camera preview could not be created")
        LaunchedEffect(error) { runRecoverableCatching { onCameraError(error) } }
        return
    }
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnScannerError by rememberUpdatedState(onScannerError)
    val currentOnCodeDetected by rememberUpdatedState(onCodeDetected)

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(context, lifecycleOwner, previewView, bindingKey) {
        val analysisExecutor = runRecoverableCatching { Executors.newSingleThreadExecutor() }
            .getOrElse { error ->
                runRecoverableCatching { currentOnCameraError(error) }
                return@DisposableEffect onDispose {
                    runRecoverableCatching { currentOnCameraReady(null) }
                }
            }
        val mainExecutor = runRecoverableCatching { ContextCompat.getMainExecutor(context) }.getOrElse { error ->
            runRecoverableCatching { onCameraError(error) }
            return@DisposableEffect onDispose { runRecoverableCatching { analysisExecutor.shutdownNow() } }
        }
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null
        var barcodeScanner: BarcodeScanner? = null
        var analyzer: MlKitQrAnalyzer? = null
        val providerFuture = runRecoverableCatching { ProcessCameraProvider.getInstance(context) }.getOrElse { error ->
            runRecoverableCatching { currentOnCameraError(error) }
            return@DisposableEffect onDispose {
                runRecoverableCatching { analysisExecutor.shutdownNow() }
                runRecoverableCatching { currentOnCameraReady(null) }
            }
        }

        runRecoverableCatching {
            providerFuture.addListener(
                {
                if (disposed) return@addListener
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    cameraProvider = provider
                    previewUseCase = preview
                    analysisUseCase = analysis
                    val maxZoomRatio = boundCamera.cameraInfo.zoomState.value?.maxZoomRatio
                        ?.takeIf { it.isFinite() && it >= 1f }
                        ?: 1f
                    val zoomOptions = ZoomSuggestionOptions.Builder { suggestedRatio ->
                        if (disposed) return@Builder false
                        val zoomState = boundCamera.cameraInfo.zoomState.value ?: return@Builder false
                        val ratio = safeCameraZoomRatio(
                            suggested = suggestedRatio,
                            minimum = zoomState.minZoomRatio,
                            maximum = zoomState.maxZoomRatio,
                        ) ?: return@Builder false
                        runRecoverableCatching { boundCamera.cameraControl.setZoomRatio(ratio) }.isSuccess
                    }
                        .setMaxSupportedZoomRatio(maxZoomRatio)
                        .build()
                    val scannerOptions = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .enableAllPotentialBarcodes()
                        .setZoomSuggestionOptions(zoomOptions)
                        .build()
                    val scanner = BarcodeScanning.getClient(scannerOptions)
                    barcodeScanner = scanner
                    val imageAnalyzer = MlKitQrAnalyzer(
                        scanner = scanner,
                        onCodeDetected = { code, resume ->
                            val accepted = executeCrashGuarded(
                                executor = mainExecutor,
                                taskName = "QR scan result delivery",
                            ) {
                                if (!disposed) {
                                    try {
                                        currentOnCodeDetected(code) { accepted ->
                                            if (!accepted) resume()
                                        }
                                    } catch (error: Throwable) {
                                        if (error.isFatalProcessError()) throw error
                                        runRecoverableCatching { currentOnScannerError(error) }
                                        resume()
                                    }
                                } else {
                                    resume()
                                }
                            }
                            if (!accepted) resume()
                        },
                        onError = { error ->
                            executeCrashGuarded(
                                executor = mainExecutor,
                                taskName = "QR scanner error delivery",
                            ) {
                                if (!disposed) currentOnScannerError(error)
                            }
                        },
                    )
                    analysis.setAnalyzer(analysisExecutor, imageAnalyzer)
                    analyzer = imageAnalyzer
                    if (!disposed) runRecoverableCatching { currentOnCameraReady(boundCamera) }
                } catch (error: Throwable) {
                    if (error.isFatalProcessError()) throw error
                    if (!disposed) runRecoverableCatching { currentOnCameraError(error) }
                }
                },
                mainExecutor,
            )
        }.onFailure { error ->
            if (!disposed) runRecoverableCatching { currentOnCameraError(error) }
        }

        onDispose {
            disposed = true
            runRecoverableCatching { analyzer?.close() }
            runRecoverableCatching { analysisUseCase?.clearAnalyzer() }
            runRecoverableCatching { barcodeScanner?.close() }
            cameraProvider?.let { provider ->
                previewUseCase?.let { runRecoverableCatching { provider.unbind(it) } }
                analysisUseCase?.let { runRecoverableCatching { provider.unbind(it) } }
            }
            runRecoverableCatching { analysisExecutor.shutdownNow() }
            runRecoverableCatching { currentOnCameraReady(null) }
        }
    }
}

internal fun safeCameraZoomRatio(suggested: Float, minimum: Float, maximum: Float): Float? {
    if (!suggested.isFinite() || !minimum.isFinite() || !maximum.isFinite() || minimum > maximum) return null
    return suggested.coerceIn(minimum, maximum)
}

private class MlKitQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCodeDetected: (String, resume: () -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val processing = AtomicBoolean(false)
    private val awaitingResult = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        if (closed.get() || awaitingResult.get() || !processing.compareAndSet(false, true)) {
            closeImageSafely(image)
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            processing.set(false)
            closeImageSafely(image)
            return
        }
        try {
            scanner.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                .addOnSuccessListener { barcodes ->
                    try {
                        val code = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                        if (!closed.get() && code != null && awaitingResult.compareAndSet(false, true)) {
                            try {
                                onCodeDetected(code) { awaitingResult.set(false) }
                            } catch (error: Throwable) {
                                awaitingResult.set(false)
                                reportError(error)
                            }
                        }
                        errorReported.set(false)
                    } catch (error: Throwable) {
                        reportError(error)
                    }
                }
                .addOnFailureListener { error ->
                    reportError(error)
                }
                .addOnCompleteListener {
                    processing.set(false)
                    closeImageSafely(image)
                }
        } catch (error: Throwable) {
            processing.set(false)
            closeImageSafely(image)
            reportError(error)
        }
    }

    private fun reportError(error: Throwable) {
        if (error.isFatalProcessError()) throw error
        if (!closed.get() && errorReported.compareAndSet(false, true)) {
            runRecoverableCatching { onError(error) }
        }
    }

    private fun closeImageSafely(image: ImageProxy) {
        runRecoverableCatching { image.close() }
    }

    fun close() {
        closed.set(true)
        awaitingResult.set(true)
    }
}
