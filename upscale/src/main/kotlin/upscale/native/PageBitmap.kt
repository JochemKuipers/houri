package upscale.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Decode/encode helpers shared by every BYTE backend. Ported from the old engine body so the
 * guards (pixel cap, OOM handling, PNG-then-WebP encode) stay identical.
 */
object PageBitmap {

    /** PNG encode ceiling; larger outputs fall through to WebP. */
    const val MAX_PNG_BYTES = 12 * 1024 * 1024

    fun decodeBounds(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
    }.getOrNull()

    fun decodeBitmap(bytes: ByteArray, maxInputPixels: Long = SimpleResampler.MAX_PIXELS): Bitmap? {
        val (width, height) = decodeBounds(bytes) ?: return null
        if (width.toLong() * height > maxInputPixels) {
            logcat(LogPriority.WARN) {
                "Upscale input too large ${width}x$height, skipping"
            }
            return null
        }
        return try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        } catch (e: OutOfMemoryError) {
            System.gc()
            logcat(LogPriority.ERROR, e) { "Upscale OOM decode ${bytes.size} bytes" }
            null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Upscale decode failed" }
            null
        }
    }

    /**
     * Encodes to the smallest of PNG / WebP that stays under [MAX_PNG_BYTES].
     *
     * OOM is caught here as it is in [decodeBitmap], and deliberately falls through to the other
     * format rather than aborting: PNG's encoder buffers into native memory outside the Java heap
     * cap, so a large page can exhaust the process during the PNG attempt and still have room for
     * the far cheaper WebP pass. Returning null would drop the upscaled page entirely, which is
     * the one outcome worse than a lossier encode.
     */
    fun compressForCache(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled) return null
        var png: ByteArray? = null
        try {
            val pngOut = java.io.ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut)) {
                png = pngOut.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: OutOfMemoryError) {
            System.gc()
            logcat(LogPriority.WARN, e) { "Upscale PNG encode OOM; trying WebP" }
            png = null
        } catch (_: Exception) {
            png = null
        }
        if (png != null && png.size <= MAX_PNG_BYTES) return png
        return try {
            val webpOut = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 95, webpOut)) return null
            webpOut.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: OutOfMemoryError) {
            System.gc()
            logcat(LogPriority.ERROR, e) { "Upscale WebP encode OOM" }
            null
        } catch (_: Exception) {
            null
        }
    }
}
