package android.graphics

/** Offline stub for check.sh: only the surface the mtl.* render path touches. */
class BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var outWidth: Int = 0
        var outHeight: Int = 0
    }

    companion object {
        @JvmStatic
        fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options? = null): Bitmap? = null
    }
}

/** Offline stub for check.sh: only the surface the mtl.* render path touches. */
class Bitmap private constructor() {
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    var isRecycled: Boolean = false
        private set
    val width: Int = 0
    val height: Int = 0

    fun copy(config: Config, isMutable: Boolean): Bitmap? = null
    fun recycle() { }
    fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean = false
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) { }
    fun getPixel(x: Int, y: Int): Int = 0

    companion object {
        @JvmStatic
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap = Bitmap()
        @JvmStatic
        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createBitmap(src: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap = Bitmap()
    }
}

/** Offline stub for check.sh: only the surface the mtl.* render path touches. */
class Canvas(bitmap: Bitmap?) {
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) { }
    fun drawText(text: String, x: Float, y: Float, paint: Paint) { }
}

/** Offline stub for check.sh: only the surface the mtl.* render path touches. */
class Color {
    companion object {
        const val WHITE: Int = -1
        const val BLACK: Int = -16777216
    }
}

/** Offline stub for check.sh: only the surface the mtl.* render path touches. */
class Paint {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Align { LEFT, CENTER, RIGHT }

    class FontMetrics {
        val ascent: Float = 0f
        val descent: Float = 0f
        val top: Float = 0f
        val bottom: Float = 0f
        val leading: Float = 0f
    }

    var color: Int = 0
    var style: Style = Style.FILL
    var isAntiAlias: Boolean = false
    var textAlign: Align = Align.LEFT
    var textSize: Float = 0f

    val fontMetrics: FontMetrics = FontMetrics()

    fun measureText(text: String): Float = 0f
}
