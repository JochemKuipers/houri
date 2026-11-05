import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import mtl.ConfigSource
import mtl.MtlConfig
import mtl.context.BreadcrumbBudget
import mtl.context.InMemoryContextStore
import mtl.context.trimBreadcrumbs
import mtl.core.ImageBounds
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlException
import mtl.core.MtlProvider
import mtl.core.OcrLine
import mtl.core.PageCheck
import mtl.core.PageCodec
import mtl.core.PageDecision
import mtl.core.PageImage
import mtl.core.ProviderCapabilities
import mtl.core.ProviderId
import mtl.core.ProviderKind
import mtl.core.ProviderSession
import mtl.core.PromptPolicy
import mtl.core.Readiness
import mtl.core.TranslatedRegion
import mtl.core.TranslationContext
import mtl.core.TranslationRequest
import mtl.core.TranslationResult
import mtl.sched.ChapterKey
import mtl.sched.ChapterQueue
import mtl.sched.MtlScheduler
import mtl.sched.PagePipeline
import mtl.sched.PagePriority
import mtl.sched.PageWork
import mtl.select.CallRequirement
import mtl.select.PolicySource
import mtl.select.ProviderRegistry
import mtl.select.ProviderSelector
import mtl.select.SelectionPolicy
import mtl.select.VisionStageRegistry
import mtl.status.StatusHub
import mtl.store.CacheIdentity
import mtl.store.CacheKeys
import mtl.store.InMemoryPageStore

/**
 * Offline spine harness for the mtl.* package: runs the provider selection, queue, page
 * pipeline and status behaviors against fakes on a plain JVM. Same contract as the other
 * harnesses here — pure logic only, a failure prints FAIL and exits non-zero.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun ok(name: String) {
    passed++
    println("PASS $name")
}

private fun fail(name: String, e: Throwable?) {
    failures.add(name + (e?.let { ": ${it.message}" } ?: ""))
    println("FAIL $name" + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
}

private inline fun scenario(name: String, block: () -> Unit) {
    try {
        block()
        ok(name)
    } catch (e: Throwable) {
        fail(name, e)
    }
}

private fun expectTrue(value: Boolean, message: String) {
    if (!value) throw AssertionError(message)
}

private fun expectEquals(expected: Any?, actual: Any?, message: String) {
    if (expected != actual) throw AssertionError("$message (expected=$expected actual=$actual)")
}

// ---------------------------------------------------------------------------
// fakes
// ---------------------------------------------------------------------------

private class FakePageImage : PageImage {
    override val width: Int = 100
    override val height: Int = 100
    override val platform: Any = Unit
    override fun close() { }
}

private class FakeModelFiles(override val requirement: ModelRequirement) : ModelFiles {
    override fun pathFor(file: String): String = "/fake/${requirement.id}/$file"
    override fun isComplete(): Boolean = true
}

private class FakeCodec(
    var bounds: ImageBounds? = ImageBounds(100, 100),
    var decoded: PageImage? = FakePageImage(),
    var webp: ByteArray? = byteArrayOf(1, 2, 3),
) : PageCodec {
    override fun boundsOf(imageBytes: ByteArray): ImageBounds? = bounds
    override fun decode(imageBytes: ByteArray, sampleSize: Int): PageImage? = decoded
    override fun encodeWebp(image: PageImage, quality: Int): ByteArray? = webp
    override fun encodeJpeg(image: PageImage, quality: Int): ByteArray? = webp
}

/** Behavior: what one translate call answers. Throw inside for a failing provider. */
private typealias SessionBehavior = suspend (TranslationRequest) -> TranslationResult

private class FakeProvider(
    val idValue: String,
    val kind: ProviderKind,
    val behavior: SessionBehavior,
    val ready: Boolean = true,
    val model: String = "$idValue-model",
    val maxConcurrency: Int = 2,
    val costTier: Int = 2,
    val latencyTier: Int = 2,
) : MtlProvider {
    override val id = ProviderId(idValue)
    override val displayName = idValue
    override val capabilities = ProviderCapabilities(
        kind = kind,
        maxConcurrency = maxConcurrency,
        costTier = costTier,
        latencyTier = latencyTier,
    )
    override val readiness = MutableStateFlow<Readiness>(if (ready) Readiness.Ready else Readiness.Unavailable("x", "down"))
    override fun modelId(): String = model

    /** Every session the pipeline opened on this provider — the "never called" spy. */
    val opened = mutableListOf<FakeSession>()

    override suspend fun open(): ProviderSession = FakeSession(behavior).also { opened.add(it) }
}

private class FakeSession(private val behavior: SessionBehavior) : ProviderSession {
    val requests = mutableListOf<TranslationRequest>()
    override suspend fun translate(request: TranslationRequest): TranslationResult {
        requests.add(request)
        return behavior(request)
    }
    override fun close() { }
}

private class FakeDetectHandle(private val regions: List<OcrLine>) :
    mtl.core.DetectHandle {
    override val stage = mtl.core.VisionStage.DETECT
    override val modelId = "fake-detect"
    override suspend fun detect(image: PageImage) = mtl.core.DetectResult(lines = regions, textMask = null)
    override fun close() { }
}

private class FakeOcrHandle : mtl.core.OcrHandle {
    override val stage = mtl.core.VisionStage.OCR
    override val modelId = "fake-ocr"
    override suspend fun recognize(image: PageImage, regions: List<OcrLine>): List<OcrLine> =
        regions.map { it.copy(text = "src-${it.regionIndex}") }
    override fun close() { }
}

private class FakeInpaintHandle : mtl.core.InpaintHandle {
    override val stage = mtl.core.VisionStage.INPAINT
    override val modelId = "fake-inpaint"
    override suspend fun inpaint(image: PageImage, regions: List<TranslatedRegion>, textMask: PageImage?) { }
    override fun close() { }
}

private class FakeRenderHandle : mtl.core.RenderHandle {
    override val stage = mtl.core.VisionStage.RENDER
    override val modelId = "fake-render"
    override suspend fun render(image: PageImage, regions: List<TranslatedRegion>): PageImage = FakePageImage()
    override fun close() { }
}

private abstract class FakeStageProviderBase(
    override val stage: mtl.core.VisionStage,
    val idValue: String,
) : mtl.core.VisionStageProvider {
    override val id = ProviderId(idValue)
    override val displayName = idValue
    override val models = listOf(ModelRequirement(id = idValue, displayName = idValue, files = listOf("a.bin"), downloadSizeBytes = 1))
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)
}

private class FakeDetectStageProvider(private val regionsProvider: () -> List<OcrLine>) :
    FakeStageProviderBase(mtl.core.VisionStage.DETECT, "fake-detect"), mtl.core.DetectStageProvider {
    override suspend fun open(models: ModelFiles): FakeDetectHandle = FakeDetectHandle(regionsProvider())
}

private class FakeOcrStageProvider : FakeStageProviderBase(mtl.core.VisionStage.OCR, "fake-ocr"), mtl.core.OcrStageProvider {
    override suspend fun open(models: ModelFiles): FakeOcrHandle = FakeOcrHandle()
}

private class FakeInpaintStageProvider : FakeStageProviderBase(mtl.core.VisionStage.INPAINT, "fake-inpaint"), mtl.core.InpaintStageProvider {
    override suspend fun open(models: ModelFiles): FakeInpaintHandle = FakeInpaintHandle()
}

private class FakeRenderStageProvider : FakeStageProviderBase(mtl.core.VisionStage.RENDER, "fake-render"), mtl.core.RenderStageProvider {
    override suspend fun open(models: ModelFiles): FakeRenderHandle = FakeRenderHandle()
}

/** One detected region at a fixed box. */
private val FP = PromptPolicy().fingerprint()
private fun regions(): List<OcrLine> = listOf(
    OcrLine(regionIndex = 0, left = 10, top = 10, right = 90, bottom = 30, text = "", confidence = 0.9f),
)

private class TestRig(
    val providers: List<FakeProvider>,
    val offlineFallback: Boolean = false,
) {
    val status = StatusHub()
    val store = InMemoryPageStore()
    val context = InMemoryContextStore()
    val codec = FakeCodec()

    val config = MtlConfig(
        enabled = true,
        sourceLang = "JA",
        targetLang = "en",
        longPageSlicingEnabled = true,
        offlineFallback = offlineFallback,
        promptPolicy = PromptPolicy(),
    )

    val selector = ProviderSelector(
        ProviderRegistry(providers),
        PolicySource { SelectionPolicy.DEFAULT },
    )

    val stages: VisionStageRegistry = VisionStageRegistry(
        detect = listOf(FakeDetectStageProvider { detectRegions }),
        ocr = listOf(FakeOcrStageProvider()),
        inpaint = listOf(FakeInpaintStageProvider()),
        render = listOf(FakeRenderStageProvider()),
    )

    /** Mutable so a scenario can empty the detector without rebuilding the pipeline. */
    var detectRegions: List<OcrLine> = regions()

    val pipeline = PagePipeline(
        selector = selector,
        stages = stages,
        codec = codec,
        store = store,
        context = context,
        status = status,
        configSource = ConfigSource { config },
    ).apply {
        modelResolver = { FakeModelFiles(it) }
    }

    val work = PageWork(
        key = ChapterKey(mangaId = 1, chapterId = 2),
        pageIndex = 3,
        imageBytes = ByteArray(2048),
        sourceLangHint = "JA",
    )

    suspend fun run(): mtl.sched.PageOutcome = pipeline.run(work)
}

// ---------------------------------------------------------------------------
// scenarios
// ---------------------------------------------------------------------------

private fun runQueueScenarios() {
    scenario("queue: pages dispatch in submission order per chapter") {
        val queue = ChapterQueue()
        val keys = (5 downTo 1).map { idx ->
            PageWork(ChapterKey(1, 1), idx, ByteArray(10), "JA")
        }.map { work ->
            queue.submit(work); work
        }
        val drained = generateSequence { queue.pollNext() }.toList()
        expectEquals(keys.map { it.pageIndex }, drained.map { it.pageIndex }, "dispatch order")
    }

    scenario("queue: visible priority beats read-ahead") {
        val queue = ChapterQueue()
        val ra = PageWork(ChapterKey(1, 1), 1, ByteArray(10), "JA", PagePriority.READ_AHEAD)
        val vis = PageWork(ChapterKey(1, 1), 2, ByteArray(10), "JA", PagePriority.VISIBLE)
        queue.submit(ra)
        queue.submit(vis)
        val first = queue.pollNext()
        expectEquals(2, first?.pageIndex, "visible page first")
    }

    scenario("queue: duplicate submission ignored while pending") {
        val queue = ChapterQueue()
        val work = PageWork(ChapterKey(1, 1), 1, ByteArray(10), "JA")
        expectTrue(queue.submit(work).result == ChapterQueue.SubmitResult.QUEUED, "first queued")
        expectTrue(queue.submit(work).result == ChapterQueue.SubmitResult.DUPLICATE, "second duplicate")
        expectEquals(1, queue.pendingCount, "single pending")
    }

    scenario("queue: cap drops the oldest and carries it to the caller") {
        val queue = ChapterQueue(pendingCap = 2)
        val first = PageWork(ChapterKey(1, 1), 1, ByteArray(10), "JA")
        queue.submit(first)
        queue.submit(PageWork(ChapterKey(1, 1), 2, ByteArray(10), "JA"))
        val outcome = queue.submit(PageWork(ChapterKey(1, 1), 3, ByteArray(10), "JA"))
        expectTrue(outcome.result == ChapterQueue.SubmitResult.DROPPED_OLDEST, "cap tripped")
        expectEquals(1, outcome.dropped?.pageIndex, "oldest dropped")
        expectEquals(2, queue.pendingCount, "cap respected")
    }

    scenario("queue: paused chapter rejects work; resume accepts again") {
        val queue = ChapterQueue()
        val key = ChapterKey(1, 1)
        queue.pause(key)
        val rejected = queue.submit(PageWork(key, 1, ByteArray(10), "JA"))
        expectTrue(rejected.result == ChapterQueue.SubmitResult.REJECTED_PAUSED, "paused rejects")
        queue.resume(key)
        val accepted = queue.submit(PageWork(key, 1, ByteArray(10), "JA"))
        expectTrue(accepted.result == ChapterQueue.SubmitResult.QUEUED, "resumed accepts")
    }

    scenario("queue: cancel drops pending and reports the chapter") {
        val queue = ChapterQueue()
        val key = ChapterKey(1, 1)
        queue.submit(PageWork(key, 1, ByteArray(10), "JA"))
        queue.submit(PageWork(key, 2, ByteArray(10), "JA"))
        val dropped = queue.cancel(key)
        expectEquals(listOf(1, 2), dropped.sorted(), "dropped pages")
        expectEquals(0, queue.pendingCount, "queue empty")
        expectTrue(queue.cancelAll().isEmpty(), "cancelAll after cancel is empty")
    }
}

private fun runSelectorScenarios() {
    scenario("selector: manual pick is honored when capable") {
        val cloud = FakeProvider("cloud", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, costTier = 3)
        val local = FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, costTier = 0)
        val selector = ProviderSelector(
            ProviderRegistry(listOf(cloud, local)),
            PolicySource { SelectionPolicy(mode = SelectionPolicy.Mode.MANUAL, manualPick = ProviderId("cloud")) },
        )
        val chain = selector.chain(CallRequirement(ProviderKind.TEXT_LINES, "JA", "en", true))
        expectEquals(listOf("cloud"), chain.map { it.id.value }, "manual pick first")
    }

    scenario("selector: fallback chain walks in declared order, unavailable entries skipped") {
        val dead = FakeProvider("dead", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, ready = false)
        val live = FakeProvider("live", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) })
        val selector = ProviderSelector(
            ProviderRegistry(listOf(dead, live)),
            PolicySource { SelectionPolicy(fallbacks = listOf(ProviderId("dead"), ProviderId("live"))) },
        )
        val chain = selector.chain(CallRequirement(ProviderKind.TEXT_LINES, "JA", "en", true))
        expectEquals(listOf("live"), chain.map { it.id.value }, "unavailable filtered")
    }

    scenario("selector: auto ranks by cost then latency") {
        val expensive = FakeProvider("expensive", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, costTier = 3)
        val cheapSlow = FakeProvider("cheapSlow", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, costTier = 0, latencyTier = 3)
        val cheapFast = FakeProvider("cheapFast", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, costTier = 0, latencyTier = 0)
        val selector = ProviderSelector(
            ProviderRegistry(listOf(expensive, cheapSlow, cheapFast)),
            PolicySource { SelectionPolicy.DEFAULT },
        )
        val chain = selector.chain(CallRequirement(ProviderKind.TEXT_LINES, "JA", "en", true))
        expectEquals(listOf("cheapFast", "cheapSlow", "expensive"), chain.map { it.id.value }, "ranked")
    }

    scenario("selector: readiness ignored when asked") {
        val dead = FakeProvider("dead", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("x")) }, ready = false)
        val selector = ProviderSelector(ProviderRegistry(listOf(dead)), PolicySource { SelectionPolicy.DEFAULT })
        expectTrue(selector.chain(CallRequirement(ProviderKind.TEXT_LINES, "JA", "en", true)).isEmpty(), "unavailable filtered")
        expectTrue(selector.chain(CallRequirement(ProviderKind.TEXT_LINES, "JA", "en", true), ignoreReadiness = true).isNotEmpty(), "ignoreReadiness includes")
    }
}

private fun runStatusScenarios() {
    scenario("status: page lifecycle and retryables") {
        val status = StatusHub()
        status.setTotalPages(1, 2, 10)
        status.pagePhase(1, 2, 3, mtl.sched.PagePhase.TRANSLATING)
        status.pageFailed(1, 2, 3, "rate limited", StatusHub.PageOutcomeKind.RATE_LIMITED)
        status.pageSkipped(1, 2, 4, "no text")
        status.pageDone(1, 2, 5, servedBy = ProviderId("local"))
        val chapter = status.chapterStatus(1, 2)!!
        expectEquals(10, chapter.totalPages, "total")
        expectEquals(1, chapter.failedCount, "failed")
        expectEquals(1, chapter.skippedCount, "skipped")
        expectEquals(1, chapter.translatedCount, "translated")
        expectEquals(setOf(3), status.retryablePages(1, 2), "skip is not retryable, failure is")
        status.updateForRetry(1, 2, status.retryablePages(1, 2))
        expectEquals(emptySet<Int>(), status.retryablePages(1, 2), "cleared")
    }
}

private fun runDecisionScenarios() {
    scenario("decision: byte gates match the old validator") {
        expectEquals("Image too small or corrupted", PageDecision.sizeRejection(ByteArray(1023)), "too small")
        expectTrue(PageDecision.sizeRejection(ByteArray(1024)) == null, "exactly min")
        expectTrue(PageDecision.sizeRejection(ByteArray(30 * 1024 * 1024)) == null, "exactly max")
        expectEquals("Image too large", PageDecision.sizeRejection(ByteArray(30 * 1024 * 1024 + 1)), "too large")
    }

    scenario("decision: bounds gates and sample size") {
        val ok = PageDecision.decide(ByteArray(2048), ImageBounds(1000, 1500), true)
        expectTrue(ok is PageCheck.Ok && ok.sampleSize == 1, "normal page full res")
        val rejected = PageDecision.decide(ByteArray(2048), ImageBounds(0, 0), true)
        expectTrue(rejected is PageCheck.Rejected, "zero bounds rejected")
        val tall = PageDecision.decide(ByteArray(2048), ImageBounds(1000, 5000), true)
        expectTrue(tall is PageCheck.Ok && tall.isTallFullRes, "tall page slices full-res")
        val sampled = PageDecision.decide(ByteArray(2048), ImageBounds(5000, 8000), true)
        expectTrue(sampled is PageCheck.Ok && sampled.sampleSize == 4, "big page sampled 4x")
    }
}

private fun runUtilityScenarios() {
    scenario("keys: identity encoding is stable and distinct") {
        val a = CacheIdentity("local", "m1", "JA", "en", FP)
        val b = a.copy(targetLang = "es")
        expectTrue(CacheKeys.forContent(ByteArray(10), a) != CacheKeys.forContent(ByteArray(10), b), "different identity, different key")
        expectEquals(CacheKeys.contentHash(ByteArray(10)), CacheKeys.contentHash(ByteArray(10)), "hash stable")
    }

    scenario("context: breadcrumb budget and trimming") {
        expectEquals(BreadcrumbBudget.DEFAULT_BUDGET, BreadcrumbBudget.forContextLength(null), "unknown context")
        expectEquals(BreadcrumbBudget.MIN_BUDGET, BreadcrumbBudget.forContextLength(100), "clamped low")
        expectEquals(BreadcrumbBudget.MAX_BUDGET, BreadcrumbBudget.forContextLength(100_000), "clamped high")
        val long = (1..300).joinToString("\n") { "line $it" }
        val trimmed = trimBreadcrumbs(long, 100)
        expectTrue(trimmed.length <= 120, "trimmed near budget")
        expectTrue(trimmed.endsWith("line 300"), "keeps the tail")
    }

    scenario("pipeline: align reconciles short, long and blank outputs") {
        expectEquals(listOf("a", "b"), PagePipeline.align(listOf("a", "b"), listOf("x", "y")), "exact")
        expectEquals(listOf("a", "y"), PagePipeline.align(listOf("a"), listOf("x", "y")), "short padded with source")
        expectEquals(listOf("a", "b"), PagePipeline.align(listOf("a", "b", "c"), listOf("x", "y")), "long truncated")
        expectEquals(listOf("x", "b"), PagePipeline.align(listOf("  ", "b"), listOf("x", "y")), "blank replaced by source")
    }
}

private fun runPipelineScenarios() = runBlocking {
    scenario("pipeline: text provider translates end to end") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        val outcome = rig.run()
        expectTrue(outcome is mtl.sched.PageOutcome.Done, "done, got $outcome")
        val done = outcome as mtl.sched.PageOutcome.Done
        expectEquals("local", done.servedBy.value, "served by")
        val chapter = rig.status.chapterStatus(1, 2)!!
        expectEquals(1, chapter.translatedCount, "status done")
        // Both stores got the result under the serving provider's identity.
        val identity = CacheIdentity("local", "local-model", "JA", "en", FP)
        expectTrue(rig.store.lookupSavedPage(CacheKeys.forSavedPage(1, 2, 3, rig.work.imageBytes, identity)) != null, "saved")
        expectTrue(rig.store.lookupContent(CacheKeys.forContent(rig.work.imageBytes, identity)) != null, "content")
    }

    scenario("pipeline: saved-page cache hit answers without a provider call") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        // Pre-populate via one full run, then run again with a fresh provider spy.
        expectTrue(rig.run() is mtl.sched.PageOutcome.Done, "first run translates")
        val spy = FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("SECOND")) })
        val rig2 = TestRig(listOf(spy))
        // rig2 shares nothing with rig; copy the saved entry over by key.
        val identity = CacheIdentity("local", "local-model", "JA", "en", FP)
        val key = CacheKeys.forSavedPage(1, 2, 3, rig.work.imageBytes, identity)
        rig2.store.storeSavedPage(key, byteArrayOf(9), "Title")
        val outcome = rig2.run()
        expectTrue(outcome is mtl.sched.PageOutcome.Cached, "cached, got $outcome")
        expectTrue(spy.opened.isEmpty(), "provider never opened")
        val chapter = rig2.status.chapterStatus(1, 2)!!
        expectEquals(1, chapter.translatedCount, "counted as cached")
    }

    scenario("pipeline: a retryable failure falls through to the next provider") {
        val failing = FakeProvider("bad", ProviderKind.TEXT_LINES, { throw MtlException.RateLimited(ProviderId("bad")) })
        val working = FakeProvider("good", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })
        val rig = TestRig(listOf(failing, working))
        val outcome = rig.run()
        expectTrue(outcome is mtl.sched.PageOutcome.Done, "done, got $outcome")
        expectEquals("good", (outcome as mtl.sched.PageOutcome.Done).servedBy.value, "second provider served")
        // Stored under the identity of the provider that actually served.
        val identity = CacheIdentity("good", "good-model", "JA", "en", FP)
        expectTrue(rig.store.lookupContent(CacheKeys.forContent(rig.work.imageBytes, identity)) != null, "stored under server identity")
    }

    scenario("pipeline: offline fallback skips without caching; off fails") {
        val failing = FakeProvider("bad", ProviderKind.TEXT_LINES, { throw MtlException.RateLimited(ProviderId("bad")) })
        val on = TestRig(listOf(failing), offlineFallback = true)
        val skipped = on.run()
        expectTrue(skipped is mtl.sched.PageOutcome.Skipped, "skipped, got $skipped")
        expectTrue(on.store.savedTitles().isEmpty(), "nothing cached")
        val off = TestRig(listOf(FakeProvider("bad", ProviderKind.TEXT_LINES, { throw MtlException.RateLimited(ProviderId("bad")) })), offlineFallback = false)
        val failed = off.run()
        expectTrue(failed is mtl.sched.PageOutcome.Failed && failed.retryable, "failed retryable, got $failed")
    }

    scenario("pipeline: no readable text is a skip, not a failure") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        rig.detectRegions = emptyList()
        val outcome = rig.run()
        expectTrue(outcome is mtl.sched.PageOutcome.Skipped, "skipped, got $outcome")
    }

    scenario("pipeline: corrupt page fails as not-actionable without a provider call") {
        val spy = FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })
        val rig = TestRig(listOf(spy))
        val outcome = rig.pipeline.run(rig.work.copy(imageBytes = ByteArray(100)))
        expectTrue(outcome is mtl.sched.PageOutcome.Failed, "failed, got $outcome")
        expectTrue(!(outcome as mtl.sched.PageOutcome.Failed).retryable, "not retryable")
    }

    scenario("pipeline: undecodable page fails as not-actionable") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        rig.codec.decoded = null
        val outcome = rig.run()
        expectTrue(outcome is mtl.sched.PageOutcome.Failed && !(outcome as mtl.sched.PageOutcome.Failed).retryable, "not-actionable, got $outcome")
    }
}

private fun runSchedulerScenarios() = runBlocking {
    scenario("scheduler: submit completes with the pipeline outcome") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = MtlScheduler(scope, rig.pipeline)
            val outcome = withTimeout(10_000) { scheduler.submit(rig.work).await() }
            expectTrue(outcome is mtl.sched.PageOutcome.Done, "done, got $outcome")
        } finally {
            scope.cancel()
        }
    }

    scenario("scheduler: duplicate submit awaits the same deferred") {
        val rig = TestRig(listOf(FakeProvider("local", ProviderKind.TEXT_LINES, { TranslationResult.Lines(listOf("Hello")) })))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = MtlScheduler(scope, rig.pipeline)
            val d1 = scheduler.submit(rig.work)
            val d2 = scheduler.submit(rig.work)
            expectTrue(d1 === d2, "same deferred")
            withTimeout(10_000) { d1.await() }
        } finally {
            scope.cancel()
        }
    }

    scenario("scheduler: cancel completes pending waiters with a skip") {
        val gate = MutableStateFlow(false)
        val gated = FakeProvider("local", ProviderKind.TEXT_LINES, { req ->
            while (!gate.value) { kotlinx.coroutines.delay(10) }
            TranslationResult.Lines(listOf("Hello"))
        })
        val rig = TestRig(listOf(gated))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scheduler = MtlScheduler(scope, rig.pipeline)
            val d = scheduler.submit(rig.work)
            scheduler.cancelChapter(ChapterKey(1, 2))
            val outcome = withTimeout(10_000) { d.await() }
            expectTrue(outcome is mtl.sched.PageOutcome.Skipped, "skipped on cancel, got $outcome")
        } finally {
            gate.value = true
            scope.cancel()
        }
    }
}

fun main() {
    runQueueScenarios()
    runSelectorScenarios()
    runStatusScenarios()
    runDecisionScenarios()
    runUtilityScenarios()
    runPipelineScenarios()
    runSchedulerScenarios()
    println()
    if (failures.isNotEmpty()) {
        println("FAIL — ${failures.size} of ${passed + failures.size} scenarios failed:")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
    println("OK — $passed scenarios passed")
}
