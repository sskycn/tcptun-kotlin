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
import androidx.compose.material.icons.rounded.ContentCopy
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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.text.input.VisualTransformation
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
internal fun SettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val startFailedMessage = stringResource(R.string.start_failed)
    var settings by rememberSaveable(stateSaver = RuntimeSettingsSaver) {
        mutableStateOf(RuntimeSettings())
    }
    var socksPortText by rememberSaveable { mutableStateOf(settings.socksPort.toString()) }
    var settingsDirty by rememberSaveable { mutableStateOf(false) }
    var savingSettings by remember { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var settingsLoaded by rememberSaveable { mutableStateOf(false) }
    var credentialsHydrated by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()
    val settingsSnackbarHostState = remember { SnackbarHostState() }
    val lanPasswordRequiredMessage = stringResource(R.string.lan_proxy_password_required)
    val lanAuthenticationGeneratedMessage = stringResource(R.string.lan_proxy_auth_generated)
    val socksPasswordLabel = stringResource(R.string.socks_password)
    val vpnState by TcptunState.state.collectAsStateWithLifecycle()
    val diagnostics = vpnState.diagnostics
    val mtuOptions = listOf("1280", "1360", "1400", "1500")
    val defaultPoolLabel = stringResource(R.string.route_outbound_proxy)
    val defaultDirectLabel = stringResource(R.string.route_outbound_direct)
    val defaultOutboundChoices = listOf(
        DefaultOutboundDynamicPool to defaultPoolLabel,
        DefaultOutboundDirect to defaultDirectLabel,
    )
    val selectedDefaultOutboundLabel = defaultOutboundChoices
        .firstOrNull { it.first == settings.defaultOutbound }
        ?.second
        ?: defaultPoolLabel

    LaunchedEffect(appContext) {
        val loadedSettings = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }
        if (settingsLoaded) {
            settings = hydrateRuntimeSettingsCredentials(settings, loadedSettings)
        } else {
            settings = loadedSettings
            socksPortText = loadedSettings.socksPort.toString()
            settingsLoaded = true
        }
        credentialsHydrated = true
    }

    if (!settingsLoaded || !credentialsHydrated) {
        BackHandler(onBack = onBack)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    fun updateSettingsDraft(next: RuntimeSettings) {
        if (savingSettings) return
        if (next != settings) settingsDirty = true
        settings = next
    }

    fun leaveSettings() {
        val socksPort = socksPortText.toIntOrNull()
        if (savingSettings || socksPort == null || socksPort !in 1..65535) return
        if (settings.socksListenAll && settings.socksPassword.isEmpty()) {
            settingsScope.launch { settingsSnackbarHostState.showDismissibleSnackbar(lanPasswordRequiredMessage) }
            return
        }
        if (!settingsDirty) {
            onBack()
            return
        }
        val next = settings.copy(socksPort = socksPort)
        savingSettings = true
        settingsScope.launch {
            try {
                val persisted = durableMutation(appContext, "runtime settings save") {
                    ProcessRuntimeSettingsMutationMutex.withLock {
                        writeUiRuntimeSettings(appContext, next).getOrThrow()
                        applyRuntimeSettings(appContext)
                        readUiRuntimeSettings(appContext)
                    }
                }.await()
                settings = persisted
                socksPortText = persisted.socksPort.toString()
                settingsDirty = false
                onBack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportUiError(error.message ?: startFailedMessage)
            } finally {
                savingSettings = false
            }
        }
    }

    BackHandler(onBack = ::leaveSettings)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(settingsSnackbarHostState) },
        topBar = {
            SettingsTopBar(onBack = ::leaveSettings)
        },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                if (!settingsDirty) {
                    val loadedSettings = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }
                    settings = loadedSettings
                    socksPortText = settings.socksPort.toString()
                }
                refreshRunningDiagnostics()
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
                        SectionTitle(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.transparent_proxy_settings),
                        )
                        ChoiceRow("MTU", settings.mtu.toString(), mtuOptions, enabled = !savingSettings) { value ->
                            updateSettingsDraft(
                                settings.copy(mtu = value.toIntOrNull() ?: RuntimeSettingsDefaults.VpnMtu),
                            )
                        }
                        ChoiceRow(
                            stringResource(R.string.local_proxy_protocol),
                            settings.localProxyProtocol,
                            LocalProxyProtocols,
                            enabled = !savingSettings,
                        ) { value ->
                            updateSettingsDraft(settings.copy(localProxyProtocol = value))
                        }
                        val socksPort = socksPortText.toIntOrNull()
                        OutlinedTextField(
                            value = socksPortText,
                            onValueChange = { value ->
                                if (savingSettings) return@OutlinedTextField
                                val digits = value.filter { it.isDigit() }.take(5)
                                socksPortText = digits
                                val port = digits.toIntOrNull()
                                if (port != null && port in 1..65535) {
                                    updateSettingsDraft(settings.copy(socksPort = port))
                                }
                            },
                            label = { FieldChromeText(stringResource(R.string.socks_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !savingSettings,
                            isError = socksPort == null || socksPort !in 1..65535,
                            supportingText = {
                                if (socksPort == null || socksPort !in 1..65535) {
                                    FieldChromeText(stringResource(R.string.socks_port_error))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            stringResource(R.string.socks_listen_all),
                            settings.socksListenAll,
                            enabled = !savingSettings,
                        ) { checked ->
                            val generated = checked && settings.socksPassword.isEmpty()
                            updateSettingsDraft(
                                if (generated) {
                                    secureRuntimeSettings(settings.copy(socksListenAll = true))
                                } else {
                                    settings.copy(socksListenAll = checked)
                                },
                            )
                            if (generated) {
                                settingsScope.launch {
                                    settingsSnackbarHostState.showDismissibleSnackbar(
                                        lanAuthenticationGeneratedMessage,
                                    )
                                }
                            }
                        }
                        ToggleRow(
                            stringResource(R.string.route_local_proxy_traffic),
                            settings.routeLocalProxyTraffic,
                            enabled = !savingSettings,
                        ) { checked ->
                            updateSettingsDraft(settings.copy(routeLocalProxyTraffic = checked))
                        }
                        Text(
                            stringResource(R.string.route_local_proxy_traffic_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChoiceRow(
                            stringResource(R.string.default_outbound),
                            selectedDefaultOutboundLabel,
                            defaultOutboundChoices.map { it.second },
                            enabled = !savingSettings,
                        ) { selected ->
                            val choice = defaultOutboundChoices.firstOrNull { it.second == selected }
                                ?: return@ChoiceRow
                            updateSettingsDraft(settings.copy(defaultOutbound = choice.first))
                        }
                        Text(
                            stringResource(R.string.default_outbound_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = settings.socksUsername,
                            onValueChange = { value ->
                                updateSettingsDraft(settings.copy(socksUsername = truncateSocksCredential(value)))
                            },
                            label = { FieldChromeText(stringResource(R.string.socks_username)) },
                            singleLine = true,
                            enabled = !savingSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = settings.socksPassword,
                            onValueChange = { value ->
                                updateSettingsDraft(settings.copy(socksPassword = truncateSocksCredential(value)))
                            },
                            label = { FieldChromeText(socksPasswordLabel) },
                            singleLine = true,
                            enabled = !savingSettings,
                            isError = settings.socksListenAll && settings.socksPassword.isEmpty(),
                            supportingText = {
                                if (settings.socksListenAll && settings.socksPassword.isEmpty()) {
                                    FieldChromeText(lanPasswordRequiredMessage)
                                }
                            },
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row {
                                    IconButton(
                                        onClick = {
                                            passwordVisible = !passwordVisible
                                        },
                                        enabled = !savingSettings,
                                    ) {
                                        Icon(
                                            imageVector = if (passwordVisible) {
                                                Icons.Rounded.VisibilityOff
                                            } else {
                                                Icons.Rounded.Visibility
                                            },
                                            contentDescription = stringResource(
                                                if (passwordVisible) R.string.hide_password else R.string.show_password,
                                            ),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                            clipboard?.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    socksPasswordLabel,
                                                    settings.socksPassword,
                                                ),
                                            )
                                        },
                                        enabled = !savingSettings && settings.socksPassword.isNotEmpty(),
                                    ) {
                                        Icon(
                                            Icons.Rounded.ContentCopy,
                                            contentDescription = stringResource(R.string.copy_password),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.native_tun_capabilities_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.socks_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.runtime_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Speed,
                            title = stringResource(R.string.logs),
                        )
                        ChoiceRow(
                            stringResource(R.string.log_level),
                            settings.logLevel,
                            LogLevels,
                            enabled = !savingSettings,
                        ) { value ->
                            updateSettingsDraft(settings.copy(logLevel = value))
                        }
                        Text(
                            stringResource(R.string.log_level_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionTitle(
                            icon = Icons.Rounded.Check,
                            title = stringResource(R.string.current_effective),
                        )
                        DiagnosticsLine("MTU", diagnostics.mtu.toString())
                        DiagnosticsLine(stringResource(R.string.log_level), settings.logLevel)
                        DiagnosticsLine(stringResource(R.string.local_proxy_protocol), settings.localProxyProtocol)
                        DiagnosticsLine(
                            stringResource(R.string.socks_listen),
                            RuntimeSettingsRepository.localSocksListenAddress(settings),
                        )
                        DiagnosticsLine(stringResource(R.string.socks_auth), if (settings.socksUsername.isNotEmpty() || settings.socksPassword.isNotEmpty()) stringResource(R.string.enabled) else stringResource(R.string.disabled))
                        DiagnosticsLine(
                            stringResource(R.string.route_local_proxy_traffic),
                            if (settings.routeLocalProxyTraffic) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                        )
                        DiagnosticsLine(
                            stringResource(R.string.default_outbound),
                            selectedDefaultOutboundLabel,
                        )
                        DiagnosticsLine(stringResource(R.string.vpn_traffic_mode), stringResource(R.string.tcp_udp))
                    }
                }
            }
        }
        }
    }
}

internal suspend fun mutateManagedRouteRules(
    context: Context,
    transform: (List<ManagedRouteRule>) -> List<ManagedRouteRule>,
): Pair<List<ManagedRouteRule>, ProfilesState> = ProcessProfileMutationMutex.withLock {
    ProcessRouteRuleMutationMutex.withLock {
        withContext(Dispatchers.IO) {
            // Always transform the latest committed snapshot. This also makes
            // flow-analysis conversion safe while route management is open in
            // another Activity instance.
            val currentRules = RouteRuleStore.loadAuthoritative(context).getOrThrow()
            val next = transform(currentRules)
            val profileSnapshot = context.profileRepository().snapshot(context)
            val authoritativeProfiles = profileSnapshot.requireAuthoritativeState()
            val possiblePlans = buildList {
                if (authoritativeProfiles.activeIds.isNotEmpty()) {
                    add(authoritativeProfiles.runPlan())
                }
                authoritativeProfiles.profiles
                    .firstOrNull { it.rawConfigJson.isBlank() }
                    ?.let { profile ->
                        // A structured run plan contains every structured
                        // profile so future hot membership updates remain possible.
                        add(authoritativeProfiles.copy(activeIds = setOf(profile.id)).runPlan())
                    }
                authoritativeProfiles.profiles
                    .filter { it.rawConfigJson.isNotBlank() }
                    .forEach { profile ->
                        add(authoritativeProfiles.copy(activeIds = setOf(profile.id)).runPlan())
                    }
            }.distinct()
            val currentRouteSize = estimatedEnabledRouteRuntimePayloadLength(currentRules)
            val nextRouteSize = estimatedEnabledRouteRuntimePayloadLength(next)
            possiblePlans.forEach { possiblePlan ->
                val candidateFailure = runCatching {
                    TcptunVpnService.preflightStartPayload(
                        context = context,
                        sourcePlan = possiblePlan,
                        managedRouteRules = next,
                    )
                }.exceptionOrNull() ?: return@forEach
                val currentConfigurationFits = runCatching {
                    TcptunVpnService.preflightStartPayload(
                        context = context,
                        sourcePlan = possiblePlan,
                        managedRouteRules = currentRules,
                    )
                }.isSuccess
                // Do not let an unrelated already-oversized imported profile
                // prevent the user from deleting or shrinking route data.
                if (currentConfigurationFits || nextRouteSize > currentRouteSize) {
                    throw candidateFailure
                }
            }
            RouteRuleStore.save(context, next).getOrThrow()
            RouteRuleStore.loadAuthoritative(context).getOrThrow() to authoritativeProfiles
        }
    }
}
