package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import android.graphics.Typeface

/**
 * Offline stub for check.sh: the native engine surface the mtl.* vision-stage adapter
 * touches. The real classes arrive from the `external/yakuyomi-engine` submodule
 * (`li.joye.yakuyomi:engine`) at build time.
 */

/** Offline stub for check.sh. */
class Pt(val x: Float, val y: Float)

/** Offline stub for check.sh. */
class TextLine(val quad: List<Pt>, val score: Float) {
    var direction: String = "h"
    var text: String = ""
    var translatedText: String = ""
    var tightQuad: List<Pt>? = null
}

/** Offline stub for check.sh. */
class TextRegion(
    val lines: List<TextLine>,
    val direction: String,
    val angle: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val boxW: Float = 0f,
    val boxH: Float = 0f,
) {
    var translatedText: String = ""
    var onArt: Boolean = false

    val sourceText: String get() = lines.joinToString("") { it.text }
    val x0: Float = lines.minOf { ln -> ln.quad.minOf { it.x } }
    val y0: Float = lines.minOf { ln -> ln.quad.minOf { it.y } }
    val x1: Float = lines.maxOf { ln -> ln.quad.maxOf { it.x } }
    val y1: Float = lines.maxOf { ln -> ln.quad.maxOf { it.y } }
}

/** Offline stub for check.sh. */
object Grouping {
    fun group(lines: List<TextLine>): List<TextRegion> = emptyList()
}

/** Offline stub for check.sh. */
class Detection(val lines: List<TextLine>, val textMask: Bitmap?)

/** Offline stub for check.sh. */
class Detector(modelPath: String, cfg: Any? = null) {
    fun detect(page: Bitmap): Detection = Detection(emptyList(), null)
    fun warmUp() { }
    fun close() { }
}

/** Offline stub for check.sh. */
class Ocr(model: String, alphabet: List<String>, cfg: Any? = null) {
    suspend fun recognize(page: Bitmap, lines: List<TextLine>, bicubic: Boolean = false) { }
    fun warmUp() { }
    fun close() { }
}

/** Offline stub for check.sh. */
class Inpainter(modelPath: String, cfg: Any? = null) {
    suspend fun inpaint(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap?, render: RenderConfig = RenderConfig()): Bitmap = page
    fun buildMask(page: Bitmap, regions: List<TextRegion>, textMask: Bitmap?, render: RenderConfig = RenderConfig()): Bitmap = page
    fun warmUp() { }
    fun close() { }
}

/** Offline stub for check.sh. */
class RenderConfig

/** Offline stub for check.sh. */
object Renderer {
    fun render(page: Bitmap, regions: List<TextRegion>, cfg: RenderConfig = RenderConfig(), tf: Typeface? = null): Bitmap = page
}
