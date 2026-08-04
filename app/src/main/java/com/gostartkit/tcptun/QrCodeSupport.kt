package com.tcptun.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodes the PNG returned by tcptun-go's profile QR bridge. */
internal fun decodeQrCodeBitmap(png: ByteArray): Bitmap {
    require(png.isNotEmpty()) { "QR code image must not be empty" }
    require(png.size <= MAX_QR_PNG_BYTES) { "QR code image is too large" }
    return BitmapFactory.decodeByteArray(png, 0, png.size)
        ?: throw IllegalArgumentException("invalid QR code image")
}

private const val MAX_QR_PNG_BYTES = 4 * 1024 * 1024
