package mtl

import kotlinx.coroutines.CoroutineScope
import mtl.context.ContextStore
import mtl.core.DetectStageProvider
import mtl.core.InpaintStageProvider
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlProvider
import mtl.core.OcrStageProvider
import mtl.core.PageCodec
import mtl.core.RenderStageProvider
import mtl.sched.MtlScheduler
import mtl.sched.PagePipeline
import mtl.sched.ProviderLanes
import mtl.select.PolicySource
import mtl.select.ProviderRegistry
import mtl.select.ProviderSelector
import mtl.select.VisionStageRegistry
import mtl.status.StatusHub
import mtl.store.PageStore

/**
 * Assembles the MTL spine from the app's seams. One object, constructed once at app scope:
 *
 * - the app supplies **values and IO** (config, toggles, gates, stores, providers, stages);
 * - the bootstrap supplies **structure** (registry, selector, pipeline, scheduler, controller);
 * - [controller] is the only member app code should touch.
 *
 * The provider list is taken as-is, in registration order — the selector breaks ranking ties
 * by that order, so callers register in their preferred display order.
 */
class MtlBootstrap(
    parentScope: CoroutineScope,
    configSource: ConfigSource,
    policySource: PolicySource,
    toggles: PerMangaToggles,
    gates: PrivacyGates,
    infoStore: MangaInfoStore,
    contextStore: ContextStore,
    pageStore: PageStore,
    codec: PageCodec,
    providers: List<MtlProvider>,
    detectStages: List<DetectStageProvider>,
    ocrStages: List<OcrStageProvider>,
    inpaintStages: List<InpaintStageProvider>,
    renderStages: List<RenderStageProvider>,
    modelResolver: (ModelRequirement) -> ModelFiles,
    budgets: mtl.StageBudgets = mtl.StageBudgets.DEFAULT,
) {

    val status = StatusHub()

    val selector = ProviderSelector(
        registry = ProviderRegistry(providers),
        policySource = policySource,
    )

    val stageRegistry = VisionStageRegistry(
        detect = detectStages,
        ocr = ocrStages,
        inpaint = inpaintStages,
        render = renderStages,
    )

    private val pipeline = PagePipeline(
        selector = selector,
        stages = stageRegistry,
        codec = codec,
        store = pageStore,
        context = contextStore,
        status = status,
        configSource = configSource,
        budgets = budgets,
        lanes = ProviderLanes(),
    ).apply { this.modelResolver = modelResolver }

    val controller = MtlController(
        scheduler = MtlScheduler(parentScope, pipeline),
        selector = selector,
        status = status,
        store = pageStore,
        contextStore = contextStore,
        configSource = configSource,
        toggles = toggles,
        gates = gates,
        infoStore = infoStore,
    )
}
