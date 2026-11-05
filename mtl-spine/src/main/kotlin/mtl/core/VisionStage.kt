package mtl.core

import kotlinx.coroutines.flow.StateFlow

/**
 * A vision pipeline stage — detection, OCR, inpainting, typesetting. Stages are providers in
 * the same sense [MtlProvider] is: swappable implementations of one contract, selected by
 * readiness, replaceable when better models land.
 *
 * Stage handles are serial: the spine runs one page's stage at a time per handle, matching
 * the non-thread-safe native sessions. Concurrency across pages is the spine's business.
 */
enum class VisionStage { DETECT, OCR, INPAINT, RENDER }

/** A model file set a stage needs installed. */
data class ModelRequirement(
    /** Stable model-set id (part of cache identity). */
    val id: String,
    val displayName: String,
    /** Files that must exist, as logical names — the resolver maps them to paths. */
    val files: List<String>,
    val downloadSizeBytes: Long,
)

/** Resolved, on-disk files for one [ModelRequirement]. */
interface ModelFiles {
    val requirement: ModelRequirement

    /** Logical file name → absolute path. */
    fun pathFor(file: String): String

    fun isComplete(): Boolean
}

/**
 * An opened, ready-to-run stage. Inputs and outputs are page-pixel coordinates on the
 * [PageImage] the spine passed in; the spine closes that image, not the stage. [close]
 * releases native sessions.
 */
sealed interface StageHandle : AutoCloseable {
    val stage: VisionStage
    val modelId: String
}

/** Detect: text lines as boxes/quads with confidence, in page pixels, plus the stroke mask. */
data class DetectResult(
    /** Detected lines, in reading order, text not yet filled. */
    val lines: List<OcrLine>,
    /**
     * The detector's glyph-stroke mask over the same page, for the inpainter's
     * stroke-precise removal. Null when the detector produced none; the inpainter then
     * falls back to its box-based mask.
     */
    val textMask: PageImage? = null,
)

/** Detect: text regions as boxes with confidence, in page pixels. */
interface DetectHandle : StageHandle {
    suspend fun detect(image: PageImage): DetectResult
}

/** OCR: text for each detected box, same order and region indexes. */
interface OcrHandle : StageHandle {
    suspend fun recognize(image: PageImage, regions: List<OcrLine>): List<OcrLine>
}

/**
 * Inpaint: erase the glyph strokes of [regions] from [image] (mutates the raster). The
 * removal set is every detected region — unreadable text is erased too, so no raw source
 * script survives beside the translation — which is why [regions] carries
 * [TranslatedRegion]s with empty text, not just boxes.
 */
interface InpaintHandle : StageHandle {
    suspend fun inpaint(image: PageImage, regions: List<TranslatedRegion>, textMask: PageImage?)
}

/** Render: typeset [regions] onto the (inpainted) image, returning the finished raster. */
interface RenderHandle : StageHandle {
    suspend fun render(image: PageImage, regions: List<TranslatedRegion>): PageImage
}

/**
 * Base contract for stage providers. Concrete stages extend with a covariant [open] so the
 * pipeline never casts handles.
 */
interface VisionStageProvider {
    val stage: VisionStage
    val id: ProviderId
    val displayName: String
    val models: List<ModelRequirement>
    val readiness: StateFlow<Readiness>

    /** Builds the native session from resolved model files. Throws [MtlException] on failure. */
    suspend fun open(models: ModelFiles): StageHandle
}

interface DetectStageProvider : VisionStageProvider {
    override suspend fun open(models: ModelFiles): DetectHandle
}

interface OcrStageProvider : VisionStageProvider {
    override suspend fun open(models: ModelFiles): OcrHandle
}

interface InpaintStageProvider : VisionStageProvider {
    override suspend fun open(models: ModelFiles): InpaintHandle
}

interface RenderStageProvider : VisionStageProvider {
    override suspend fun open(models: ModelFiles): RenderHandle
}
