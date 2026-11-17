package ca.mpreg.imagedecoder

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Offline stub for check.sh: the surface the app uses from the upstream libvips decoder
 * (`ca.mpreg:imagedecoder`, consumed as a Maven artifact so its classes are not in the
 * Gradle cache as sources). Signatures mirror the published AAR's metadata.
 */
class ImageDecoder private constructor() : Closeable {
    val isHdr: Boolean get() = false
    val hdrHeadroom: Float get() = 0f
    val width: Int get() = 0
    val height: Int get() = 0
    val pages: Int get() = 1
    val isAnimated: Boolean get() = false
    val format: Format get() = Format.UNKNOWN

    enum class Format { PNG, JPEG, TIFF, WEBP, GIF, AVIF, HEIF, JXL, JP2, UNKNOWN }

    class Frame : Closeable {
        val width: Int get() = 0
        val height: Int get() = 0
        val duration: Int get() = 0
        val image: ByteBuffer get() = ByteBuffer.allocate(0)
        override fun close() {}
    }

    fun decode(region: Rect? = null, sampleSize: Int = 1): Frame = Frame()
    fun decodeNext(): Frame = Frame()
    fun decodeToBitmap(region: Rect? = null, sampleSize: Int = 1): Bitmap = throw UnsupportedOperationException()
    override fun close() {}

    companion object {
        fun open(inputStream: InputStream, complete: Boolean = true): ImageDecoder = ImageDecoder()
    }
}
