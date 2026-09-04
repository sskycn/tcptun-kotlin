package com.tcptun.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal const val PrivacyPolicyUrl = "https://tcptun.com/privacy/"
internal const val VpnDisclosurePreferences = "tcptun_vpn_disclosure"
internal const val VpnDisclosureConsentKey = "accepted_version"
internal const val CurrentVpnDisclosureVersion = 1

internal fun hasAcceptedCurrentVpnDisclosure(context: Context): Boolean =
    context.getSharedPreferences(VpnDisclosurePreferences, Context.MODE_PRIVATE)
        .getInt(VpnDisclosureConsentKey, 0) == CurrentVpnDisclosureVersion

internal fun acceptCurrentVpnDisclosure(context: Context): Boolean =
    context.getSharedPreferences(VpnDisclosurePreferences, Context.MODE_PRIVATE)
        .edit()
        .putInt(VpnDisclosureConsentKey, CurrentVpnDisclosureVersion)
        .commit()

internal fun openPrivacyPolicy(context: Context): Result<Unit> = runRecoverableCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PrivacyPolicyUrl))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
internal fun VpnDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.vpn_disclosure_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.vpn_disclosure_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenPrivacyPolicy) {
                    Text(stringResource(R.string.privacy_policy))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.vpn_disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.vpn_disclosure_decline))
            }
        },
    )
}
