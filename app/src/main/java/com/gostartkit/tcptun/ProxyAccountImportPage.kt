package com.tcptun.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun ProxyAccountImportPage(
    account: LocalProxyUser,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.proxy_accounts_save_failed)
    var settingsRead by remember(account) { mutableStateOf<RuntimeSettingsRead.Success?>(null) }
    var unavailable by remember(account) { mutableStateOf<RuntimeSettingsRead.Unavailable?>(null) }
    var loadAttempt by remember(account) { mutableIntStateOf(0) }
    var loaded by remember(account) { mutableStateOf(false) }
    var saving by remember(account) { mutableStateOf(false) }
    var passwordVisible by remember(account) { mutableStateOf(false) }

    fun reload() {
        loaded = false
        loadAttempt += 1
    }

    fun finishImport(updateExisting: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                persistImportedProxyAccount(appContext, account, updateExisting)
                onFinished()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showDismissibleSnackbar(saveFailed)
                reload()
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(appContext, account, loadAttempt) {
        when (val current = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }) {
            is RuntimeSettingsRead.Success -> {
                settingsRead = current
                unavailable = null
            }
            is RuntimeSettingsRead.Unavailable -> {
                settingsRead = null
                unavailable = current
            }
        }
        loaded = true
    }

    BackHandler(enabled = !saving, onBack = onCancel)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.import_proxy_account),
                onBack = { if (!saving) onCancel() },
            )
        },
        snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            !loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            unavailable != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(unavailable?.safeDescription.orEmpty())
                    Button(onClick = ::reload) { Text(stringResource(R.string.retry)) }
                }
            }

            settingsRead != null -> {
                val settings = requireNotNull(settingsRead).settings
                val plan = planLocalProxyAccountImport(settings, account)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.socks_username),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = account.username.ifBlank { stringResource(R.string.empty_username) },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.socks_password),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (passwordVisible) account.password else "••••••••",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
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
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.proxy_account_bearer_secret_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    when (plan) {
                        LocalProxyAccountImportPlan.Add -> {
                            Text(stringResource(R.string.proxy_account_import_new))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = onCancel, enabled = !saving) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { finishImport(false) }, enabled = !saving) {
                                    Text(stringResource(R.string.import_action))
                                }
                            }
                        }

                        LocalProxyAccountImportPlan.AlreadyPresent -> {
                            Text(stringResource(R.string.proxy_account_already_exists))
                            FilledTonalButton(
                                onClick = onFinished,
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.view_proxy_accounts)) }
                        }

                        is LocalProxyAccountImportPlan.Conflict -> {
                            Text(
                                stringResource(
                                    R.string.proxy_account_conflict,
                                    account.username.ifBlank { stringResource(R.string.empty_username) },
                                ),
                            )
                            TextButton(
                                onClick = onCancel,
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.cancel)) }
                            TextButton(
                                onClick = onFinished,
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.keep_existing_account)) }
                            Button(
                                onClick = { finishImport(true) },
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.update_with_scanned_account)) }
                        }

                        LocalProxyAccountImportPlan.LimitReached -> {
                            Text(
                                text = stringResource(R.string.proxy_account_limit_reached, MaxLocalProxyUsers),
                                color = MaterialTheme.colorScheme.error,
                            )
                            FilledTonalButton(
                                onClick = onFinished,
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.view_proxy_accounts)) }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun persistImportedProxyAccount(
    appContext: android.content.Context,
    account: LocalProxyUser,
    updateExisting: Boolean,
) {
    durableMutation(appContext, "proxy account import") {
        ProcessRuntimeSettingsMutationMutex.withLock {
            val current = readUiRuntimeSettings(appContext) as? RuntimeSettingsRead.Success
                ?: throw IllegalStateException(RuntimeSettingsUnavailableSafeDescription)
            val next = applyLocalProxyAccountImport(current.settings, account, updateExisting)
            if (next != current.settings) {
                writeUiRuntimeSettings(appContext, current, next).getOrThrow()
                applyRuntimeSettings(appContext)
            }
        }
    }.await()
}
