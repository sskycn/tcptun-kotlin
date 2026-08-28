package com.tcptun.client

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle

internal fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
    sensitive: Boolean,
): Boolean = runRecoverableCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return@runRecoverableCatching false
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    true
}.getOrDefault(false)
