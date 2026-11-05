package mtl.select

import mtl.core.DetectStageProvider
import mtl.core.InpaintStageProvider
import mtl.core.OcrStageProvider
import mtl.core.Readiness
import mtl.core.RenderStageProvider
import mtl.core.VisionStage
import mtl.core.VisionStageProvider

/**
 * The vision stages available to the page pipeline: one registry, one answer per stage.
 *
 * Today the answer is "the first ready provider in registration order" — the built-in native
 * stages register first, better models land as additional providers that outrank them by
 * being registered first (or by a user override, once the settings surface exists). The
 * pipeline never reaches into implementations.
 */
class VisionStageRegistry(
    detect: List<DetectStageProvider>,
    ocr: List<OcrStageProvider>,
    inpaint: List<InpaintStageProvider>,
    render: List<RenderStageProvider>,
) {
    private val byStage: Map<VisionStage, List<VisionStageProvider>> = mapOf(
        VisionStage.DETECT to detect,
        VisionStage.OCR to ocr,
        VisionStage.INPAINT to inpaint,
        VisionStage.RENDER to render,
    )

    fun providers(stage: VisionStage): List<VisionStageProvider> = byStage[stage].orEmpty()

    /** First ready provider for [stage], or null — the pipeline's single stage decision. */
    fun best(stage: VisionStage): VisionStageProvider? =
        providers(stage).firstOrNull { it.readiness.value is Readiness.Ready }

    /**
     * Whether every stage the full pipeline needs is ready. UI-facing: this is what tells the
     * user "the vision models are not installed" before any page is attempted.
     */
    fun pipelineReady(): Boolean =
        VisionStage.entries.all { best(it) != null }

    fun missingStages(): List<VisionStage> =
        VisionStage.entries.filter { best(it) == null }
}
