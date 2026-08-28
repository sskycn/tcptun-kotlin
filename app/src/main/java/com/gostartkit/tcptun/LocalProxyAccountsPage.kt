package com.tcptun.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun LocalProxyAccountsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailedMessage = stringResource(R.string.proxy_accounts_save_failed)
    val accountRequiredMessage = stringResource(R.string.proxy_account_required_for_lan)
    val shareCodeCopiedMessage = stringResource(R.string.proxy_account_share_code_copied)
    val shareCodeCopyFailedMessage = stringResource(R.string.proxy_account_share_code_copy_failed)
    val shareCodeClipboardLabel = stringResource(R.string.proxy_account_share_code)
    val shareFailedMessage = stringResource(R.string.share_proxy_account_failed)
    var authoritativeSettings by remember { mutableStateOf<RuntimeSettingsRead.Success?>(null) }
    var settingsUnavailable by remember { mutableStateOf<RuntimeSettingsRead.Unavailable?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var readAttempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var editingUserIndex by remember { mutableStateOf<Int?>(null) }
    var editingUsername by remember { mutableStateOf("") }
    var editingPassword by remember { mutableStateOf("") }
    var editingPasswordVisible by remember { mutableStateOf(false) }
    var deletingUserIndex by remember { mutableStateOf<Int?>(null) }
    var qrUser by remember { mutableStateOf<LocalProxyUser?>(null) }
    var sharingUser by remember { mutableStateOf<LocalProxyUser?>(null) }

    fun clearEditor() {
        editingUserIndex = null
        editingUsername = ""
        editingPassword = ""
        editingPasswordVisible = false
    }

    fun leavePage() {
        if (saving) return
        clearEditor()
        deletingUserIndex = null
        qrUser = null
        sharingUser = null
        onBack()
    }

    fun copyShareCode(user: LocalProxyUser) {
        scope.launch {
            val copied = runRecoverableCatching {
                val payload = withContext(Dispatchers.Default) { LocalProxyAccountCodec.encode(user) }
                copyTextToClipboard(
                    context = context,
                    label = shareCodeClipboardLabel,
                    text = payload,
                    sensitive = true,
                )
            }.getOrDefault(false)
            snackbarHostState.showDismissibleSnackbar(
                if (copied) shareCodeCopiedMessage else shareCodeCopyFailedMessage,
            )
        }
    }

    fun shareConfirmed(user: LocalProxyUser) {
        sharingUser = null
        scope.launch {
            val shared = runRecoverableCatching {
                val payload = withContext(Dispatchers.Default) { LocalProxyAccountCodec.encode(user) }
                shareProxyAccountPayload(context, payload)
            }.isSuccess
            if (!shared) snackbarHostState.showDismissibleSnackbar(shareFailedMessage)
        }
    }

    fun persistSettings(next: RuntimeSettings, onSuccess: () -> Unit) {
        val expected = authoritativeSettings ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                val persisted = durableMutation(appContext, "local proxy accounts save") {
                    ProcessRuntimeSettingsMutationMutex.withLock {
                        writeUiRuntimeSettings(appContext, expected, next).getOrThrow()
                        applyRuntimeSettings(appContext)
                        readUiRuntimeSettings(appContext).let { refreshed ->
                            refreshed as? RuntimeSettingsRead.Success
                                ?: throw IllegalStateException(RuntimeSettingsUnavailableSafeDescription)
                        }
                    }
                }.await()
                authoritativeSettings = persisted
                settingsUnavailable = null
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportUiError(error.message ?: saveFailedMessage)
                clearEditor()
                deletingUserIndex = null
                var canShowSaveFailure = false
                when (val refreshed = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }) {
                    is RuntimeSettingsRead.Success -> {
                        authoritativeSettings = refreshed
                        settingsUnavailable = null
                        canShowSaveFailure = true
                    }
                    is RuntimeSettingsRead.Unavailable -> {
                        authoritativeSettings = null
                        settingsUnavailable = refreshed
                    }
                }
                if (canShowSaveFailure) snackbarHostState.showDismissibleSnackbar(saveFailedMessage)
            } finally {
                saving = false
            }
        }
    }

    LaunchedEffect(appContext, readAttempt) {
        when (val loaded = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }) {
            is RuntimeSettingsRead.Success -> {
                authoritativeSettings = loaded
                settingsUnavailable = null
            }
            is RuntimeSettingsRead.Unavailable -> {
                authoritativeSettings = null
                settingsUnavailable = loaded
            }
        }
        pageLoaded = true
    }

    BackHandler(onBack = ::leavePage)

    if (!pageLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    settingsUnavailable?.let { unavailable ->
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { LocalProxyAccountsTopBar(onBack = ::leavePage) },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = unavailable.safeDescription,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = {
                        pageLoaded = false
                        readAttempt += 1
                    }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
        return
    }

    val settings = authoritativeSettings?.settings ?: return
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocalProxyAccountsTopBar(onBack = ::leavePage) },
        snackbarHost = { AutoDismissSnackbarHost(snackbarHostState) },
    ) { padding ->
        PullRefreshContainer(
            onRefresh = {
                if (!saving && editingUserIndex == null && deletingUserIndex == null) {
                    when (val refreshed = withContext(Dispatchers.IO) { readUiRuntimeSettings(appContext) }) {
                        is RuntimeSettingsRead.Success -> authoritativeSettings = refreshed
                        is RuntimeSettingsRead.Unavailable -> {
                            authoritativeSettings = null
                            settingsUnavailable = refreshed
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemSpacing),
            ) {
                if (settings.localProxyUsers.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.proxy_accounts_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(settings.localProxyUsers) { index, user ->
                    LocalProxyAccountRow(
                        index = index,
                        user = user,
                        enabled = !saving,
                        onEdit = {
                            editingUserIndex = index
                            editingUsername = user.username
                            editingPassword = user.password
                            editingPasswordVisible = false
                        },
                        onShowQrCode = { qrUser = user },
                        onShare = { sharingUser = user },
                        onCopyShareCode = { copyShareCode(user) },
                        onDelete = {
                            if (settings.socksListenAll && settings.localProxyUsers.size == 1) {
                                scope.launch {
                                    snackbarHostState.showDismissibleSnackbar(accountRequiredMessage)
                                }
                            } else {
                                deletingUserIndex = index
                            }
                        },
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            editingUserIndex = -1
                            editingUsername = ""
                            editingPassword = generateLanProxyPassword()
                            editingPasswordVisible = false
                        },
                        enabled = !saving && settings.localProxyUsers.size < MaxLocalProxyUsers,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_account))
                    }
                    if (settings.localProxyUsers.size >= MaxLocalProxyUsers) {
                        Text(
                            text = stringResource(R.string.proxy_account_limit_reached, MaxLocalProxyUsers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    editingUserIndex?.let { index ->
        val duplicate = settings.localProxyUsers.withIndex().any { (existingIndex, user) ->
            existingIndex != index && user.username == editingUsername
        }
        val passwordRequired = settings.socksListenAll && editingPassword.isEmpty()
        LocalProxyAccountEditorDialog(
            adding = index < 0,
            username = editingUsername,
            password = editingPassword,
            passwordVisible = editingPasswordVisible,
            duplicateUsername = duplicate,
            passwordRequired = passwordRequired,
            saving = saving,
            onUsernameChange = { editingUsername = truncateSocksCredential(it) },
            onPasswordChange = { editingPassword = truncateSocksCredential(it) },
            onPasswordVisibilityChange = { editingPasswordVisible = !editingPasswordVisible },
            onGeneratePassword = { editingPassword = generateLanProxyPassword() },
            onDismiss = ::clearEditor,
            onSave = {
                val user = LocalProxyUser(editingUsername, editingPassword)
                val next = if (index < 0) {
                    addLocalProxyAccount(settings, user)
                } else {
                    editLocalProxyAccount(settings, index, user)
                }
                persistSettings(next, ::clearEditor)
            },
        )
    }

    deletingUserIndex?.let { index ->
        settings.localProxyUsers.getOrNull(index)?.let { user ->
            AlertDialog(
                onDismissRequest = { if (!saving) deletingUserIndex = null },
                title = { Text(stringResource(R.string.delete_account)) },
                text = {
                    Text(
                        stringResource(
                            R.string.delete_proxy_account_confirmation,
                            user.username.ifBlank { stringResource(R.string.empty_username) },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            persistSettings(deleteLocalProxyAccount(settings, index)) {
                                deletingUserIndex = null
                            }
                        },
                        enabled = !saving,
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deletingUserIndex = null },
                        enabled = !saving,
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    qrUser?.let { user ->
        ProxyAccountQrCodeDialog(
            account = user,
            onDismiss = { qrUser = null },
            onCopy = {
                qrUser = null
                copyShareCode(user)
            },
            onShare = {
                qrUser = null
                sharingUser = user
            },
        )
    }

    sharingUser?.let { user ->
        ProxyAccountShareConfirmationDialog(
            account = user,
            onDismiss = { sharingUser = null },
            onConfirm = { shareConfirmed(user) },
        )
    }
}

@Composable
private fun LocalProxyAccountsTopBar(onBack: () -> Unit) {
    AppTopBar(
        title = stringResource(R.string.proxy_accounts),
        onBack = onBack,
    )
}

@Composable
private fun LocalProxyAccountRow(
    index: Int,
    user: LocalProxyUser,
    enabled: Boolean,
    onEdit: () -> Unit,
    onShowQrCode: () -> Unit,
    onShare: () -> Unit,
    onCopyShareCode: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(user) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.username.ifBlank { stringResource(R.string.empty_username) },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = enabled,
                    modifier = Modifier.testTag(localProxyAccountActionsTestTag(index)),
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_account)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_qr_code)) },
                        leadingIcon = { Icon(Icons.Rounded.QrCode2, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShowQrCode()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_share_code)) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCopyShareCode()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_account)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

internal fun localProxyAccountActionsTestTag(index: Int): String = "proxy-account-actions-$index"

@Composable
private fun LocalProxyAccountEditorDialog(
    adding: Boolean,
    username: String,
    password: String,
    passwordVisible: Boolean,
    duplicateUsername: Boolean,
    passwordRequired: Boolean,
    saving: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibilityChange: () -> Unit,
    onGeneratePassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (adding) R.string.add_account else R.string.edit_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.socks_username)) },
                    singleLine = true,
                    enabled = !saving,
                    isError = duplicateUsername,
                    supportingText = {
                        if (duplicateUsername) Text(stringResource(R.string.duplicate_username))
                    },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.socks_password)) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = passwordRequired,
                    supportingText = {
                        if (passwordRequired) Text(stringResource(R.string.proxy_account_required_for_lan))
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onPasswordVisibilityChange, enabled = !saving) {
                                Icon(
                                    if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.hide_password else R.string.show_password,
                                    ),
                                )
                            }
                            IconButton(onClick = onGeneratePassword, enabled = !saving) {
                                Icon(
                                    Icons.Rounded.Tune,
                                    contentDescription = stringResource(R.string.generate_password),
                                )
                            }
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !saving && !duplicateUsername && !passwordRequired,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
