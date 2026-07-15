package com.tcptun.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.king.wechat.qrcode.WeChatQRCodeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private enum class CameraPermissionState {
    Requesting,
    Granted,
    Denied,
}

@OptIn(ExperimentalMaterial3Api::class)
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
                        fontWeight = FontWeight.SemiBold,
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

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(context, lifecycleOwner, previewView, bindingKey) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = WeChatQrAnalyzer(
            onCodeDetected = { code, resume ->
                mainExecutor.execute {
                    if (!currentOnCodeDetected(code)) resume()
                }
            },
            onError = { error ->
                mainExecutor.execute { currentOnScannerError(error) }
            },
        )
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

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
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setOutputImageRotationEnabled(true)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    val boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    cameraProvider = provider
                    previewUseCase = preview
                    analysisUseCase = analysis
                    currentOnCameraReady(boundCamera)
                } catch (error: Throwable) {
                    currentOnCameraError(error)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            analyzer.pause()
            analysisUseCase?.clearAnalyzer()
            cameraProvider?.let { provider ->
                previewUseCase?.let { provider.unbind(it) }
                analysisUseCase?.let { provider.unbind(it) }
            }
            analysisExecutor.shutdown()
            currentOnCameraReady(null)
        }
    }
}

private class WeChatQrAnalyzer(
    private val onCodeDetected: (String, resume: () -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val awaitingResult = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private var lastAnalysisAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (awaitingResult.get() || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysisAt = now
        try {
            val bitmap = image.toBitmap()
            val code = try {
                WeChatQrEngine.detect(bitmap).firstOrNull { it.isNotBlank() }
            } finally {
                bitmap.recycle()
            }
            if (code != null && awaitingResult.compareAndSet(false, true)) {
                onCodeDetected(code) { awaitingResult.set(false) }
            }
            errorReported.set(false)
        } catch (error: Throwable) {
            if (errorReported.compareAndSet(false, true)) onError(error)
        } finally {
            image.close()
        }
    }

    fun pause() {
        awaitingResult.set(true)
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 300L
    }
}

private object WeChatQrEngine {
    private val lock = Any()

    @Volatile
    private var initialized = false

    fun initialize(context: Context): Result<Unit> = runCatching {
        if (initialized) return@runCatching
        synchronized(lock) {
            if (!initialized) {
                OpenCvRuntime.ensureInitialized()
                WeChatQRCodeDetector.init(context)
                WeChatQRCodeDetector.getScaleFactor()
                initialized = true
            }
        }
    }

    fun detect(bitmap: Bitmap): List<String> {
        check(initialized) { "QR code scanner is not initialized" }
        return WeChatQRCodeDetector.detectAndDecode(bitmap)
    }
}
