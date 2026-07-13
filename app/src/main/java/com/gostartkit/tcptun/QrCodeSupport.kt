package com.tcptun.client

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.OpenCV
import org.opencv.core.Mat
import org.opencv.objdetect.QRCodeEncoder
import org.opencv.objdetect.QRCodeEncoder_Params
import java.util.Arrays

internal object OpenCvRuntime {
    private val lock = Any()

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (!initialized) {
                check(OpenCV.initOpenCV()) { "OpenCV native library failed to load" }
                initialized = true
            }
        }
    }
}

internal fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    require(content.isNotBlank()) { "QR code content must not be blank" }
    require(size > 0) { "QR code size must be positive" }
    OpenCvRuntime.ensureInitialized()

    val params = QRCodeEncoder_Params().apply {
        set_correction_level(QRCodeEncoder.CORRECT_LEVEL_M)
        set_mode(QRCodeEncoder.MODE_AUTO)
    }
    val encoded = Mat()
    QRCodeEncoder.create(params).encode(content, encoded)
    try {
        val rows = encoded.rows()
        val columns = encoded.cols()
        check(rows > 0 && columns > 0) { "OpenCV returned an empty QR code" }
        val moduleCount = maxOf(rows, columns) + QUIET_ZONE_MODULES * 2
        val moduleSize = size / moduleCount
        require(moduleSize > 0) { "QR code size is too small for this content" }
        val renderedSize = moduleCount * moduleSize
        val start = (size - renderedSize) / 2
        val modules = ByteArray(rows * columns)
        check(encoded.get(0, 0, modules) == modules.size) { "Unable to read encoded QR code" }
        val pixels = IntArray(size * size) { Color.WHITE }

        for (moduleY in 0 until rows) {
            for (moduleX in 0 until columns) {
                val value = modules[moduleY * columns + moduleX].toInt() and 0xff
                if (value >= BLACK_THRESHOLD) continue
                val pixelX = start + (moduleX + QUIET_ZONE_MODULES) * moduleSize
                val pixelY = start + (moduleY + QUIET_ZONE_MODULES) * moduleSize
                for (offsetY in 0 until moduleSize) {
                    val rowStart = (pixelY + offsetY) * size + pixelX
                    Arrays.fill(pixels, rowStart, rowStart + moduleSize, Color.BLACK)
                }
            }
        }

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    } finally {
        encoded.release()
    }
}

private const val QUIET_ZONE_MODULES = 4
private const val BLACK_THRESHOLD = 128
