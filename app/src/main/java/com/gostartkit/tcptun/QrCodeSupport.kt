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

/**
 * Renders a high-contrast, pixel-aligned QR code.
 *
 * Optimizations for camera recognition:
 * - ECC level M (good density/robustness balance without a center logo)
 * - ISO quiet zone of 4 modules
 * - Integer module pixels only (no fractional modules)
 * - Minimum module size so dense payloads stay scannable on screen
 * - Pure black/white modules on an exact-fit bitmap
 */
internal fun generateQrCodeBitmap(
    content: String,
    size: Int,
): Bitmap {
    require(content.isNotBlank()) { "QR code content must not be blank" }
    require(size > 0) { "QR code size must be positive" }
    OpenCvRuntime.ensureInitialized()

    val params = QRCodeEncoder_Params().apply {
        // M ≈ 15% recovery: fewer modules than H, larger cells, better phone-camera scan.
        set_correction_level(QRCodeEncoder.CORRECT_LEVEL_M)
        set_mode(QRCodeEncoder.MODE_AUTO)
    }
    val encoded = Mat()
    QRCodeEncoder.create(params).encode(content, encoded)
    try {
        val rows = encoded.rows()
        val columns = encoded.cols()
        check(rows > 0 && columns > 0) { "OpenCV returned an empty QR code" }

        val dataModules = maxOf(rows, columns)
        val moduleCount = dataModules + QUIET_ZONE_MODULES * 2
        val moduleSize = maxOf(MIN_MODULE_PIXELS, size / moduleCount)
        require(moduleSize > 0) { "QR code size is too small for this content" }

        // Exact multiple of module size — avoids sub-pixel edges when scaled with nearest-neighbor.
        val renderedSize = moduleCount * moduleSize
        val modules = ByteArray(rows * columns)
        check(encoded.get(0, 0, modules) == modules.size) { "Unable to read encoded QR code" }
        val pixels = IntArray(renderedSize * renderedSize) { QR_LIGHT }

        val origin = QUIET_ZONE_MODULES * moduleSize
        for (moduleY in 0 until rows) {
            for (moduleX in 0 until columns) {
                val value = modules[moduleY * columns + moduleX].toInt() and 0xff
                if (value >= BLACK_THRESHOLD) continue
                val pixelX = origin + moduleX * moduleSize
                val pixelY = origin + moduleY * moduleSize
                for (offsetY in 0 until moduleSize) {
                    val rowStart = (pixelY + offsetY) * renderedSize + pixelX
                    Arrays.fill(pixels, rowStart, rowStart + moduleSize, QR_DARK)
                }
            }
        }

        return Bitmap.createBitmap(renderedSize, renderedSize, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, renderedSize, 0, 0, renderedSize, renderedSize)
        }
    } finally {
        encoded.release()
    }
}

/** ISO/IEC 18004 quiet zone. */
private const val QUIET_ZONE_MODULES = 4

/** Keep modules large enough for screen-to-camera scanning of denser payloads. */
private const val MIN_MODULE_PIXELS = 10

private const val BLACK_THRESHOLD = 128

/** Pure contrast; avoid near-black greys that reduce detector confidence. */
private const val QR_LIGHT = Color.WHITE
private const val QR_DARK = Color.BLACK
