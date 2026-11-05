package mtl.provider

import mtl.core.DetectStageProvider
import mtl.core.InpaintStageProvider
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlProvider
import mtl.core.OcrStageProvider
import mtl.core.PageCodec
import mtl.core.RenderStageProvider
import okhttp3.OkHttpClient

/**
 * The flavor seam between app wiring and what a build can actually offer.
 *
 * The `mtl` flavor supplies the real providers (cloud, Gemini Nano, local GGUF, image
 * service) and the native vision stages; the no-MTL flavor supplies empty lists, so the
 * spine assembles, runs its gates, and simply finds nothing able to serve a page — which
 * is exactly what that build promises. App code never sees the difference.
 */
interface MtlComponents {

    /** Text/vision/full-page providers, in registration (tie-break) order. */
    fun providers(codec: PageCodec, client: OkHttpClient): List<MtlProvider>

    fun detectStages(): List<DetectStageProvider>
    fun ocrStages(): List<OcrStageProvider>
    fun inpaintStages(): List<InpaintStageProvider>
    fun renderStages(): List<RenderStageProvider>

    /**
     * Resolves a stage provider's [ModelRequirement] to concrete files. Flavors whose
     * stages resolve models internally may return an always-complete answer.
     */
    fun modelResolver(): (ModelRequirement) -> ModelFiles

    /** Whether the build offers any provider at all (drives settings' availability copy). */
    fun hasProviders(): Boolean
}
