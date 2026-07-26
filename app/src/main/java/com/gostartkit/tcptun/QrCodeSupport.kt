package com.tcptun.client

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders a high-contrast QR code with an ISO-sized quiet zone. */
internal fun generateQrCodeBitmap(
    content: String,
    size: Int,
): Bitmap {
    require(content.isNotBlank()) { "QR code content must not be blank" }
    require(content.length <= MaxProfileUriLength) { "QR code content is too large" }
    require(size in 1..MAX_QR_BITMAP_SIZE) { "QR code size is out of range" }

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        ),
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}

private const val QUIET_ZONE_MODULES = 4
private const val MAX_QR_BITMAP_SIZE = 2_048
