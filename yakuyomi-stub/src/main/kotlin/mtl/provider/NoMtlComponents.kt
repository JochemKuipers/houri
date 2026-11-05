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
 * No-MTL build: the spine (`:mtl-spine` module) assembles with nothing able to serve a
 * page. Its gates still run (so per-manga toggles and status surfaces behave identically)
 * and every translation attempt resolves to a clean provider-unavailable failure instead
 * of half a pipeline. This class and [MtlComponentsContainer] are the flavor-specific
 * difference — the dependency is on the shared spine module, never on engine sources.
 */
class NoMtlComponents : MtlComponents {

    override fun providers(codec: PageCodec, client: OkHttpClient): List<MtlProvider> = emptyList()
    override fun detectStages(): List<DetectStageProvider> = emptyList()
    override fun ocrStages(): List<OcrStageProvider> = emptyList()
    override fun inpaintStages(): List<InpaintStageProvider> = emptyList()
    override fun renderStages(): List<RenderStageProvider> = emptyList()
    override fun modelResolver(): (ModelRequirement) -> ModelFiles = { requirement ->
        error("no-MTL build has no models to resolve for ${requirement.id}")
    }
    override fun hasProviders(): Boolean = false
}
