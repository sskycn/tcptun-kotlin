package com.tcptun.client

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun createProxyAccountShareIntent(payload: String): Intent {
    require(payload.startsWith("A1:") && payload.length <= 771) { "invalid A1 proxy account payload" }
    return Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, payload)
}

internal fun shareProxyAccountPayload(context: Context, payload: String) {
    context.startActivity(
        Intent.createChooser(
            createProxyAccountShareIntent(payload),
            context.getString(R.string.share_proxy_account),
        ),
    )
}

@Composable
internal fun ProxyAccountShareConfirmationDialog(
    account: LocalProxyUser,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_proxy_account)) },
        text = {
            Text(
                stringResource(
                    R.string.proxy_account_share_confirmation,
                    account.username.ifBlank { stringResource(R.string.empty_username) },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ProxyAccountQrCodeDialog(
    account: LocalProxyUser,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bitmapResult by produceState<Result<Bitmap>?>(initialValue = null, account) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerationMutex.withLock {
                currentCoroutineContext().ensureActive()
                val result = runRecoverableCatching {
                    decodeQrCodeBitmap(LocalProxyAccountCodec.encodeQrCode(account))
                }
                currentCoroutineContext().ensureActive()
                result
            }
        }
    }
    if (bitmapResult == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.proxy_account_qr_code)) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
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
            title = { Text(stringResource(R.string.proxy_account_qr_code)) },
            text = { Text(stringResource(R.string.proxy_account_qr_code_failed)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DialogShape,
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
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
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.proxy_account_qr_code),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = account.username.ifBlank { stringResource(R.string.empty_username) },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    shape = QrCardShape,
                    color = Color.White,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(
                            R.string.proxy_account_qr_code_description,
                            account.username.ifBlank { stringResource(R.string.empty_username) },
                        ),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.proxy_account_bearer_secret_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
