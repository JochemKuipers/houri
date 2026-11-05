package mtl.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import exh.yakuyomi.PageImageEncoder
import mtl.core.BitmapPageImage
import mtl.core.ImageBounds
import mtl.core.PageCodec
import mtl.core.PageImage

/**
 * The Android [PageCodec]: bounds/full decode through [BitmapFactory], encode through the
 * module's [PageImageEncoder] (JPEG for vision input, WEBP for output and cache).
 *
 * Every [PageImage] this codec produces owns its bitmap — decode allocates fresh, and the
 * spine's close at the end of the page run is what recycles it. Stage-produced images
 * (detection masks, renderer output) wrap their own bitmaps the same way.
 */
class AndroidCodec : PageCodec {

    override fun boundsOf(imageBytes: ByteArray): ImageBounds? {
        if (imageBytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return ImageBounds(width = opts.outWidth, height = opts.outHeight)
    }

    override fun decode(imageBytes: ByteArray, sampleSize: Int): PageImage? {
        if (imageBytes.isEmpty()) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts) ?: return null
        return BitmapPageImage(bitmap, ownsBitmap = true)
    }

    override fun encodeWebp(image: PageImage, quality: Int): ByteArray? {
        val bitmap = image.platform as? Bitmap ?: return null
        if (bitmap.isRecycled) return null
        return runCatching { PageImageEncoder.toWebp(bitmap, quality) }.getOrNull()
    }

    override fun encodeJpeg(image: PageImage, quality: Int): ByteArray? {
        val bitmap = image.platform as? Bitmap ?: return null
        if (bitmap.isRecycled) return null
        return runCatching { PageImageEncoder.toJpeg(bitmap) }.getOrNull()
    }
}
