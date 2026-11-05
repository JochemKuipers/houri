package mtl.provider

import android.graphics.Bitmap
import exh.yakuyomi.YakuyomiEngine
import kotlinx.coroutines.flow.MutableStateFlow
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import mtl.core.BitmapPageImage
import mtl.core.DetectHandle
import mtl.core.DetectResult
import mtl.core.DetectStageProvider
import mtl.core.InpaintHandle
import mtl.core.InpaintStageProvider
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlException
import mtl.core.OcrHandle
import mtl.core.OcrLine
import mtl.core.OcrStageProvider
import mtl.core.PageImage
import mtl.core.ProviderId
import mtl.core.QuadPoint
import mtl.core.Readiness
import mtl.core.RenderHandle
import mtl.core.RenderStageProvider
import mtl.core.TranslatedRegion
import mtl.core.VisionStage

/**
 * The existing native pipeline (`li.joye.yakuyomi.engine.*`, one shared [YakuyomiEngine])
 * expressed as four [mtl.core.VisionStageProvider]s, so the spine composes the same
 * detect → OCR → group → inpaint → render chain the old whole-pipeline path ran — with the
 * text provider now a spine-selected [mtl.core.MtlProvider] instead of a pipeline argument.
 *
 * The native sessions are expensive and not thread-safe, so all four stages share the
 * engine's single built [YakuyomiEngine.NativeVision]; handles are thin views over it and
 * their [close] is a no-op — session lifetime stays with the engine. Concurrency across
 * pages is the spine's business (stage handles are serial per page by contract).
 *
 * Fidelity notes:
 * - [Grouping.group] runs inside the OCR stage and stamps [OcrLine.regionIndex]; the
 *   spine's [mtl.core.RegionGroup]s therefore match the native regions one-to-one.
 * - Quads, tight quads, direction and angle round-trip through [OcrLine]/[TranslatedRegion],
 *   so vertical/tilted typesetting is unchanged.
 * - `onArt` cannot cross the SPI (the inpainter sets it on its own region objects), so the
 *   render stage re-derives the outline decision from the renderer's color mode — the same
 *   fallback the renderer applies when a region carries no art flag.
 */
class NativeDetectStage(
    private val engine: YakuyomiEngine,
    override val models: List<ModelRequirement>,
) : DetectStageProvider {

    override val stage = VisionStage.DETECT
    override val id = ProviderId("native-detect")
    override val displayName = "Text detection (native)"
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)

    override suspend fun open(models: ModelFiles): DetectHandle = NativeDetectHandle(engine.visionOrThrow())

    private class NativeDetectHandle(private val vision: YakuyomiEngine.NativeVision) : DetectHandle {
        override val stage = VisionStage.DETECT
        override val modelId = "native-detect"

        override suspend fun detect(image: PageImage): DetectResult {
            val page = image.platform as? Bitmap
                ?: throw MtlException.QualityRejected("native detect needs a Bitmap page")
            val detection = try {
                vision.components.detector.detect(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.PipelineCrash("native detect failed", e)
            }
            val lines = detection.lines.map { it.toOcrLine() }
            val mask = detection.textMask?.let { BitmapPageImage(it, ownsBitmap = true) }
            return DetectResult(lines = lines, textMask = mask)
        }

        override fun close() { }
    }
}

class NativeOcrStage(
    private val engine: YakuyomiEngine,
    override val models: List<ModelRequirement>,
) : OcrStageProvider {

    override val stage = VisionStage.OCR
    override val id = ProviderId("native-ocr")
    override val displayName = "OCR (native)"
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)

    override suspend fun open(models: ModelFiles): OcrHandle = NativeOcrHandle(engine.visionOrThrow())

    private class NativeOcrHandle(private val vision: YakuyomiEngine.NativeVision) : OcrHandle {
        override val stage = VisionStage.OCR
        override val modelId = "native-ocr"

        override suspend fun recognize(image: PageImage, regions: List<OcrLine>): List<OcrLine> {
            val page = image.platform as? Bitmap
                ?: throw MtlException.QualityRejected("native OCR needs a Bitmap page")
            // Rebuild the native lines in the same order the detect stage produced; OCR
            // fills text and direction in place.
            val nativeLines = regions.map { it.toNativeLine() }
            try {
                vision.components.ocr.recognize(page, nativeLines)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.PipelineCrash("native OCR failed", e)
            }
            // Group exactly as the old pipeline did, then stamp the region assignment back
            // onto the spine's lines: regionIndex, direction and angle travel with the line.
            val grouped = try {
                Grouping.group(nativeLines)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.PipelineCrash("native grouping failed", e)
            }
            val lineToRegion = HashMap<TextLine, TextRegion>(nativeLines.size)
            for (region in grouped) for (line in region.lines) lineToRegion[line] = region
            return regions.mapIndexed { index, ocrLine ->
                val native = nativeLines[index]
                val region = lineToRegion[native]
                ocrLine.copy(
                    text = native.text,
                    direction = region?.direction ?: native.direction,
                    angle = region?.angle ?: ocrLine.angle,
                    regionIndex = region?.let { grouped.indexOf(it) } ?: -1,
                )
            }
        }

        override fun close() { }
    }
}

class NativeInpaintStage(
    private val engine: YakuyomiEngine,
    override val models: List<ModelRequirement>,
) : InpaintStageProvider {

    override val stage = VisionStage.INPAINT
    override val id = ProviderId("native-inpaint")
    override val displayName = "Inpaint (native)"
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)

    override suspend fun open(models: ModelFiles): InpaintHandle = NativeInpaintHandle(engine.visionOrThrow())

    private class NativeInpaintHandle(private val vision: YakuyomiEngine.NativeVision) : InpaintHandle {
        override val stage = VisionStage.INPAINT
        override val modelId = "native-inpaint"

        override suspend fun inpaint(image: PageImage, regions: List<TranslatedRegion>, textMask: PageImage?) {
            val page = image.platform as? Bitmap
                ?: throw MtlException.QualityRejected("native inpaint needs a Bitmap page")
            val mask = textMask?.platform as? Bitmap
            if (mask == null) {
                // No stroke mask from detect — the eraser would only blank artwork; the renderer
                // still strokes/fills text on top, so an omitted inpaint is the honest fallback.
                return
            }
            val nativeRegions = regions.map { it.toNativeRegion() }
            try {
                // Mutates the page raster in place, exactly like the old pipeline's inpaint
                // stage; the returned bitmap is the same instance and is ignored.
                vision.components.inpainter.inpaint(page, nativeRegions, mask, vision.renderConfig)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.PipelineCrash("native inpaint failed", e)
            }
        }

        override fun close() { }
    }
}

class NativeRenderStage(
    private val engine: YakuyomiEngine,
    override val models: List<ModelRequirement>,
) : RenderStageProvider {

    override val stage = VisionStage.RENDER
    override val id = ProviderId("native-render")
    override val displayName = "Typesetting (native)"
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)

    override suspend fun open(models: ModelFiles): RenderHandle = NativeRenderHandle(engine.visionOrThrow())

    private class NativeRenderHandle(private val vision: YakuyomiEngine.NativeVision) : RenderHandle {
        override val stage = VisionStage.RENDER
        override val modelId = "native-render"

        override suspend fun render(image: PageImage, regions: List<TranslatedRegion>): PageImage {
            val page = image.platform as? Bitmap
                ?: throw MtlException.QualityRejected("native render needs a Bitmap page")
            val nativeRegions = regions.map { it.toNativeRegion() }
            val rendered = try {
                Renderer.render(page, nativeRegions, vision.renderConfig, vision.typeface)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.PipelineCrash("native render failed", e)
            }
            // Fresh bitmap from the renderer's copy — the wrapper owns it, the spine closes it.
            return BitmapPageImage(rendered, ownsBitmap = true)
        }

        override fun close() { }
    }
}

/** [YakuyomiEngine.nativeVision] with the readiness failure the spine expects. */
private fun YakuyomiEngine.visionOrThrow(): YakuyomiEngine.NativeVision =
    nativeVision(targetLang = null)
        ?: throw MtlException.ModelMissing(listOf("detector", "OCR", "inpainter"))

// ---- spine <-> native conversions ----

private fun TextLine.toOcrLine(): OcrLine {
    val quadPoints = quad.map { QuadPoint(it.x, it.y) }
    val box = boundsOf(quadPoints)
    return OcrLine(
        regionIndex = -1,
        left = box.first,
        top = box.second,
        right = box.third,
        bottom = box.fourth,
        text = text,
        confidence = score,
        quad = quadPoints,
        tightQuad = tightQuad?.map { QuadPoint(it.x, it.y) },
        direction = direction,
    )
}

private fun OcrLine.toNativeLine(): TextLine {
    val pts = if (quad.isNotEmpty()) {
        quad.map { Pt(it.x, it.y) }
    } else {
        listOf(
            Pt(left.toFloat(), top.toFloat()),
            Pt(right.toFloat(), top.toFloat()),
            Pt(right.toFloat(), bottom.toFloat()),
            Pt(left.toFloat(), bottom.toFloat()),
        )
    }
    return TextLine(pts, confidence).apply {
        direction = this@toNativeLine.direction
        text = this@toNativeLine.text
        tightQuad = this@toNativeLine.tightQuad?.map { Pt(it.x, it.y) }
    }
}

private fun TranslatedRegion.toNativeRegion(): TextRegion {
    val members = lines.map { it.toNativeLine() }
    val region = if (members.isNotEmpty()) {
        TextRegion(members, direction, angle)
    } else {
        // No member lines (pure-JVM stage outputs): synthesize one line from the box so the
        // native renderer still has quad geometry to typeset against.
        val corners = listOf(
            Pt(left.toFloat(), top.toFloat()),
            Pt(right.toFloat(), top.toFloat()),
            Pt(right.toFloat(), bottom.toFloat()),
            Pt(left.toFloat(), bottom.toFloat()),
        )
        TextRegion(listOf(TextLine(corners, 1f)), direction, angle)
    }
    region.translatedText = translatedText
    return region
}

private fun boundsOf(points: List<QuadPoint>): QuadrupleInt {
    if (points.isEmpty()) return QuadrupleInt(0, 0, 0, 0)
    return QuadrupleInt(
        points.minOf { it.x }.toInt(),
        points.minOf { it.y }.toInt(),
        points.maxOf { it.x }.toInt(),
        points.maxOf { it.y }.toInt(),
    )
}

/** A 4-int box without inventing a public type for an adapter-local detail. */
private data class QuadrupleInt(val first: Int, val second: Int, val third: Int, val fourth: Int)
