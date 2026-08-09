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
internal fun EditProfilePage(initial: AppConfig, onBack: () -> Unit, onSave: (AppConfig) -> Unit) {
    var config by rememberSaveable(initial.id, stateSaver = AppConfigSaver) {
        mutableStateOf(initial.boundedForEditor())
    }
    var useFullConfig by rememberSaveable(initial.id) {
        mutableStateOf(initial.rawConfigJson.isNotBlank())
    }
    var formError by rememberSaveable(initial.id) { mutableStateOf("") }
    var validating by remember(initial.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val invalidProfileMessage = stringResource(R.string.invalid_profile_link)

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditTopBar(
                title = if (initial.serverHost.isBlank() && initial.rawConfigJson.isBlank()) {
                    stringResource(R.string.add_profile)
                } else {
                    stringResource(R.string.edit_profile)
                },
                onBack = onBack,
                saveEnabled = !validating,
                onSave = {
                    if (validating) return@EditTopBar
                    validating = true
                    scope.launch {
                        try {
                            var candidate = config
                            while (true) {
                                withContext(Dispatchers.Default) { validateImportedProfile(candidate) }
                                val latest = config
                                if (latest == candidate) {
                                    onSave(candidate)
                                    break
                                }
                                candidate = latest
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            val message = safeUiErrorMessage(failure.message.orEmpty(), invalidProfileMessage)
                            formError = message
                            reportUiError(message)
                        } finally {
                            validating = false
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (formError.isNotBlank()) {
                    Text(
                        formError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                        OutlinedTextField(
                            value = config.name,
                            onValueChange = {
                                config = config.copy(name = it.take(MaxProfileNameInputLength))
                            },
                            label = { FieldChromeText(stringResource(R.string.name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            label = stringResource(R.string.full_config_mode),
                            checked = useFullConfig,
                            enabled = !validating,
                        ) { enabled ->
                            if (!enabled) {
                                useFullConfig = false
                                config = config.copy(rawConfigJson = "")
                                formError = ""
                            } else {
                                validating = true
                                scope.launch {
                                    try {
                                        var candidate = config
                                        while (true) {
                                            val generatedConfig = withContext(Dispatchers.Default) {
                                                val generated = candidate.toBridgeJson(
                                                    localListenAddr = "127.0.0.1:1080",
                                                )
                                                requireSafeJsonNesting(generated)
                                                candidate.copy(
                                                    rawConfigJson = JSONObject(generated).toString(2),
                                                )
                                            }
                                            val latest = config
                                            if (latest == candidate) {
                                                config = generatedConfig
                                                useFullConfig = true
                                                formError = ""
                                                break
                                            }
                                            candidate = latest
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        val message = safeUiErrorMessage(
                                            failure.message.orEmpty(),
                                            invalidProfileMessage,
                                        )
                                        useFullConfig = false
                                        formError = message
                                        reportUiError(message)
                                    } finally {
                                        validating = false
                                    }
                                }
                            }
                        }
                        if (useFullConfig) {
                            Text(
                                stringResource(R.string.full_config_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = config.rawConfigJson,
                                onValueChange = { config = config.copy(rawConfigJson = it.take(MaxProfileImportLength)) },
                                label = { FieldChromeText(stringResource(R.string.full_config_json)) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 18,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            val selectedSecurity = when {
                                config.tunnelSecurity.equals("reality", ignoreCase = true) -> "reality"
                                config.tls -> "tls"
                                else -> "none"
                            }
                            val isReality = selectedSecurity == "reality"
                            val carrierOptions = when {
                                config.protocol != "native" -> listOf("tcp")
                                isReality -> listOf("tcp", "auto", "quic")
                                selectedSecurity == "tls" -> listOf("tcp", "quic")
                                else -> listOf("tcp")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.serverHost,
                                    onValueChange = {
                                        config = config.copy(serverHost = it.take(MaxProfileHostInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.server_address)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.serverPort,
                                    onValueChange = {
                                        config = config.copy(serverPort = it.filter(Char::isDigit).take(5))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.port)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.52f),
                                )
                            }
                            ChoiceRow(
                                stringResource(R.string.protocol),
                                config.protocol,
                                AppConfig.Protocols,
                            ) {
                                config = config.copy(
                                    protocol = it,
                                    carrierMode = if (it == "native") config.carrierMode else "tcp",
                                    carrierUdpMode = if (it == "native") config.carrierUdpMode else "",
                                )
                                if (it != "native") {
                                    config = config.withoutResumableMux().withoutEch().copy(
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    )
                                }
                            }
                            ChoiceRow(
                                stringResource(R.string.field_transport),
                                config.transport,
                                AppConfig.Transports,
                                enabled = !isReality,
                            ) {
                                config = config.copy(transport = it)
                                if (it != "raw") {
                                    config = config.withoutResumableMux().withoutEch()
                                }
                            }
                            OutlinedTextField(
                                value = config.token,
                                onValueChange = { config = config.copy(token = it.take(MaxProfileUriLength)) },
                                label = { FieldChromeText(stringResource(R.string.field_token)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.sni,
                                    onValueChange = {
                                        config = config.copy(sni = it.take(MaxProfileHostInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_sni)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = config.path,
                                    onValueChange = { config = config.copy(path = it.take(MaxProfileUriLength)) },
                                    label = { FieldChromeText(stringResource(R.string.field_path)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            ChoiceRow(
                                stringResource(R.string.field_security),
                                selectedSecurity,
                                AppConfig.SecurityOptions,
                            ) { security ->
                                config = when (security) {
                                    "tls" -> config.copy(
                                        tunnelSecurity = "",
                                        tls = true,
                                        carrierMode = config.carrierMode.takeIf { it == "quic" } ?: "tcp",
                                        carrierUdpMode = config.carrierUdpMode.takeIf {
                                            config.carrierMode == "quic"
                                        }.orEmpty(),
                                    ).withoutResumableMux().withoutEch()
                                    "reality" -> config.copy(
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        realityFingerprint = config.realityFingerprint.ifBlank { "chrome" },
                                        carrierMode = if (config.protocol == "native" && config.mux) {
                                            config.carrierMode.takeIf { it in AppConfig.CarrierModes && it != "tcp" }
                                                ?: "auto"
                                        } else {
                                            "tcp"
                                        },
                                    ).withoutEch()
                                    else -> config.copy(
                                        tunnelSecurity = "",
                                        tls = false,
                                        tlsInsecure = false,
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    ).withoutResumableMux()
                                }
                            }
                            OutlinedTextField(
                                value = config.flow,
                                onValueChange = {
                                    config = config.copy(flow = it.take(MaxProfileChoiceInputLength))
                                },
                                label = { FieldChromeText(stringResource(R.string.field_flow)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (isReality) {
                                OutlinedTextField(
                                    value = config.realityPublicKey,
                                    onValueChange = {
                                        config = config.copy(realityPublicKey = it.take(MaxRealityKeyInputLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_reality_public_key)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.realityFingerprint,
                                        onValueChange = {
                                            config = config.copy(
                                                realityFingerprint = it.take(MaxProfileChoiceInputLength),
                                            )
                                        },
                                        label = { FieldChromeText(stringResource(R.string.field_fingerprint)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.realityShortId,
                                        onValueChange = {
                                            config = config.copy(
                                                realityShortId = it.take(MaxProfileChoiceInputLength),
                                            )
                                        },
                                        label = { FieldChromeText(stringResource(R.string.field_short_id)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (isReality) {
                                OutlinedTextField(
                                    value = config.realitySpiderX,
                                    onValueChange = {
                                        config = config.copy(realitySpiderX = it.take(MaxProfileUriLength))
                                    },
                                    label = { FieldChromeText(stringResource(R.string.field_spider_x)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (selectedSecurity == "tls") {
                                ToggleRow(stringResource(R.string.field_tls_insecure), config.tlsInsecure) {
                                    config = config.copy(tlsInsecure = it)
                                }
                            }
                            val canEnableEch =
                                config.protocol == "native" &&
                                    config.transport == "raw" &&
                                    selectedSecurity == "none" &&
                                    config.carrierMode.ifBlank { "tcp" } == "tcp" &&
                                    !config.muxResume
                            ToggleRow(
                                stringResource(R.string.field_ech_client_hello),
                                config.echEnabled,
                                enabled = canEnableEch || config.echEnabled,
                            ) { enabled ->
                                config = if (enabled) {
                                    config.withoutResumableMux().copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "",
                                        tls = false,
                                        tlsInsecure = false,
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        echEnabled = true,
                                        echPorts = config.echPorts.ifBlank { DefaultEchPorts },
                                    )
                                } else {
                                    config.withoutEch()
                                }
                            }
                            if (config.echEnabled) {
                                Text(
                                    stringResource(R.string.ech_client_hello_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = config.echPublicName,
                                    onValueChange = {
                                        config = config.copy(
                                            echPublicName = it.take(MaxProfileHostInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_public_name))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = config.echPublicKey,
                                    onValueChange = {
                                        config = config.copy(
                                            echPublicKey = it.take(MaxEchKeyInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_public_key))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = config.echPorts,
                                    onValueChange = {
                                        config = config.copy(
                                            echPorts = it
                                                .filter { char ->
                                                    char.isDigit() || char == ',' || char.isWhitespace()
                                                }
                                                .take(MaxProfileChoiceInputLength),
                                        )
                                    },
                                    label = {
                                        FieldChromeText(stringResource(R.string.field_ech_ports))
                                    },
                                    supportingText = {
                                        FieldChromeText(stringResource(R.string.field_ech_ports_hint))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            ToggleRow(
                                stringResource(R.string.field_mux),
                                config.mux,
                            ) {
                                config = config.copy(mux = it)
                                if (!it) {
                                    config = config.withoutResumableMux().copy(
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        muxMaxSessions = 0,
                                        muxMaxStreamsPerSession = 0,
                                        muxWarmSpare = 0,
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    )
                                }
                            }
                            ChoiceRow(
                                stringResource(R.string.field_carrier_mode),
                                config.carrierMode.ifBlank { "tcp" },
                                carrierOptions,
                            ) { mode ->
                                config = when (mode) {
                                    "auto" -> config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        realityFingerprint = config.realityFingerprint.ifBlank { "chrome" },
                                        mux = true,
                                        carrierMode = "auto",
                                    ).withoutEch()
                                    "quic" -> config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tls = !isReality,
                                        mux = true,
                                        carrierMode = "quic",
                                        carrierUdpMode = config.carrierUdpMode.ifBlank { "auto" },
                                        realityFingerprint = if (isReality) {
                                            config.realityFingerprint.ifBlank { "chrome" }
                                        } else {
                                            config.realityFingerprint
                                        },
                                    ).withoutResumableMux().withoutEch()
                                    else -> config.copy(
                                        carrierMode = "tcp",
                                        carrierUdpMode = "",
                                        carrierInitialStreamReceiveWindow = 0,
                                        carrierMaxStreamReceiveWindow = 0,
                                        carrierInitialConnectionReceiveWindow = 0,
                                        carrierMaxConnectionReceiveWindow = 0,
                                    ).withoutResumableMux()
                                }
                            }
                            if (config.mux && config.carrierMode in setOf("quic", "auto")) {
                                ChoiceRow(
                                    stringResource(R.string.field_carrier_udp_mode),
                                    config.carrierUdpMode.ifBlank { "reliable" },
                                    listOf("reliable", "auto", "datagram"),
                                ) { mode -> config = config.copy(carrierUdpMode = mode) }
                            }
                            if (config.mux) {
                                val effectiveMaxSessions = config.muxMaxSessions.takeIf { it > 0 } ?: 4
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.muxMaxSessions
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxMaxSessions = value
                                                    .filter(Char::isDigit)
                                                    .take(2)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_max_sessions))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_max_sessions_hint),
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = config.muxMaxSessions !in 0..32,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.muxWarmSpare
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxWarmSpare = value
                                                    .filter(Char::isDigit)
                                                    .take(2)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_warm_spares))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(
                                                    R.string.field_mux_warm_spares_hint,
                                                    effectiveMaxSessions - 1,
                                                ),
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = config.muxWarmSpare !in 0 until effectiveMaxSessions,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                OutlinedTextField(
                                    value = config.muxMaxStreamsPerSession
                                        .takeIf { it > 0 }
                                        ?.toString()
                                        .orEmpty(),
                                    onValueChange = { value ->
                                        config = config.copy(
                                            muxMaxStreamsPerSession = value
                                                .filter(Char::isDigit)
                                                .take(4)
                                                .toIntOrNull()
                                                ?: 0,
                                        )
                                    },
                                    label = {
                                        FieldChromeText(
                                            stringResource(R.string.field_mux_max_streams_per_session),
                                        )
                                    },
                                    supportingText = {
                                        FieldChromeText(
                                            stringResource(
                                                R.string.field_mux_max_streams_per_session_hint,
                                            ),
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = config.muxMaxStreamsPerSession !in 0..4096,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val canResumeMux =
                                config.mux &&
                                    config.protocol == "native" &&
                                    config.transport == "raw" &&
                                    selectedSecurity == "reality" &&
                                    config.carrierMode.equals("auto", ignoreCase = true)
                            ToggleRow(
                                stringResource(R.string.field_mux_resume),
                                config.muxResume,
                                enabled = canResumeMux || config.muxResume,
                            ) { enabled ->
                                config = if (enabled) {
                                    config.copy(
                                        protocol = "native",
                                        transport = "raw",
                                        tunnelSecurity = "reality",
                                        tls = false,
                                        tlsInsecure = false,
                                        mux = true,
                                        carrierMode = "auto",
                                        muxResume = true,
                                    ).withoutEch()
                                } else {
                                    config.withoutResumableMux()
                                }
                            }
                            if (config.muxResume) {
                                Text(
                                    stringResource(R.string.mux_resume_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = config.muxResumeTimeoutMillis
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxResumeTimeoutMillis = value
                                                    .filter(Char::isDigit)
                                                    .take(6)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_resume_timeout))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_resume_timeout_hint),
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = config.muxResumeBufferSize
                                            .takeIf { it > 0 }
                                            ?.toString()
                                            .orEmpty(),
                                        onValueChange = { value ->
                                            config = config.copy(
                                                muxResumeBufferSize = value
                                                    .filter(Char::isDigit)
                                                    .take(8)
                                                    .toIntOrNull()
                                                    ?: 0,
                                            )
                                        },
                                        label = {
                                            FieldChromeText(stringResource(R.string.field_mux_resume_buffer))
                                        },
                                        supportingText = {
                                            FieldChromeText(
                                                stringResource(R.string.field_mux_resume_buffer_hint),
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.native_tun_capabilities_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTopBar(
    title: String,
    onBack: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
) {
    AppTopBar(
        title = title,
        onBack = onBack,
        actions = {
            FilledTonalButton(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}


