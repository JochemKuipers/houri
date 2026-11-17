package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import mihon.app.di.globalAppGraph
import mihon.core.archive.CbzCrypto
import mihon.core.archive.CbzCrypto.getCoverStream
import mihon.core.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.AppMemoryMonitor
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.shouldDowngradeToRgb565
import tachiyomi.decoder.ImageDecoder
import java.io.BufferedInputStream

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = globalAppGraph.context

    override suspend fun decode(): DecodeResult {
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        val decoder = resources.source().use {
            coverStream.use { coverStream ->
                ImageDecoder.newInstance(coverStream ?: it.inputStream(), options.cropBorders, displayProfile)
            }
        }
        // SY <--

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        var sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )
        val isJxl = ImageUtil.findImageType(resources.source().peek().inputStream().buffered().use { it }) == ImageUtil.ImageType.JXL
        if (isJxl) {
            val cap = ImageUtil.lowRamJxlMaxDimension(context)
            val lowRamSample = ImageUtil.lowRamSampleSize(srcWidth, srcHeight, cap)
            sampleSize = maxOf(sampleSize, lowRamSample)
            val hardCap = 8192
            if (maxOf(srcWidth, srcHeight) / sampleSize > hardCap) {
                val hardSample = ImageUtil.lowRamSampleSize(srcWidth, srcHeight, hardCap)
                sampleSize = maxOf(sampleSize, hardSample)
            }
        }

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        if (isJxl) {
            runCatching { mihon.app.di.globalAppGraph.achievementManager.onJxlDecoded() }
        }

        val useHardware = options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)
        if (useHardware) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        } else if (isJxl && bitmap.config != Bitmap.Config.RGB_565) {
            // KMK --> Was gated on `DeviceUtil.isLowRamDevice(context)` and a flat 32MB threshold.
            // The device-size question is now the live pressure and the page-size question is the
            // page's own decoded bytes - see `shouldDowngradeToRgb565`. The practical difference: a
            // 12GB phone that has gone TIGHT now halves a huge page instead of keeping full colour
            // and risking the device, and a 2GB phone with headroom keeps full colour on a page it
            // can comfortably hold.
            //
            // `sampleIfStale` rather than a plain read: this runs on every JXL page, and the point
            // of the check is *now*, not "whatever startup saw". The refresh is bounded to one
            // binder call a second across all decodes, so the live answer costs nothing measurable.
            val estBytes = bitmap.width.toLong() * bitmap.height * 4
            if (shouldDowngradeToRgb565(estBytes, AppMemoryMonitor.sampleIfStale(context).pressure)) {
                val rgb565 = bitmap.copy(Bitmap.Config.RGB_565, false)
                if (rgb565 != null) {
                    bitmap.recycle()
                    bitmap = rgb565
                }
            }
        }
        // KMK <--

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // KMK --> A request that set the new-decoder extra asked for NewImageDecoder
            // specifically, whatever the bytes turn out to be. Both factories claim
            // AVIF/JXL/HEIF, and this one is registered first (see App.kt), so an AVIF cover used
            // to be decoded here and handed back as a coil3.BitmapImage - which the caller,
            // having asked for NewImageDecoder.DecodeResultImage, then failed to cast and killed
            // the app with on the main thread from inside the cover dialog's target lambda.
            if (options.newDecoder) return null
            // KMK <--
            return if (options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}
