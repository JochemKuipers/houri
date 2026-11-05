package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.Typeface
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.RenderConfig

/**
 * Offline stub for check.sh: the old-module facade surface the mtl.* vision adapter uses.
 * The real classes live in this same Gradle module (`exh.yakuyomi.*`) and are compiled
 * together in the real build; the stub only satisfies the adapter's type references offline.
 */

/** Offline stub for check.sh. */
class NativeComponents(
    val detector: Detector,
    val ocr: Ocr,
    val inpainter: Inpainter,
) {
    fun closeAll() { }
}

/** Offline stub for check.sh. */
class YakuyomiEngine {
    class NativeVision(
        val components: NativeComponents,
        val renderConfig: RenderConfig,
        val typeface: Typeface?,
    )

    fun nativeVision(targetLang: String?): NativeVision? = null
}
