package mtl.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import mtl.ConfigSource
import mtl.MtlConfig
import mtl.StageBudgets
import mtl.context.BreadcrumbBudget
import mtl.context.ContextStore
import mtl.core.DetectResult
import mtl.core.DetectStageProvider
import mtl.core.InpaintStageProvider
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlException
import mtl.core.MtlProvider
import mtl.core.OcrLine
import mtl.core.OcrStageProvider
import mtl.core.PageCheck
import mtl.core.PageCodec
import mtl.core.PageDecision
import mtl.core.PageImage
import mtl.core.ProviderKind
import mtl.core.ProviderSession
import mtl.core.RegionGroup
import mtl.core.RenderStageProvider
import mtl.core.TranslatedRegion
import mtl.core.TranslationContext
import mtl.core.TranslationRequest
import mtl.core.TranslationResult
import mtl.core.VisionStage
import mtl.core.VisionStageProvider
import mtl.requirementFor
import mtl.select.ProviderSelector
import mtl.select.VisionStageRegistry
import mtl.status.StatusHub
import mtl.store.CacheIdentity
import mtl.store.CacheKeys
import mtl.store.PageStore

/**
 * The per-page pipeline: cache → validate → decode → (detect → OCR) → translate →
 * inpaint → render → encode → store.
 *
 * One page, one run, explicit phases. Every stage has its own timeout ([StageBudgets]) and
 * every stage boundary is a cancellation point — the old pipeline's single 90/600-second
 * per-page timeout is gone, so a hung OCR no longer eats a page's whole budget before
 * anything notices.
 *
 * Fallback policy: the request walks the selector's chain; a retryable failure (or a broken
 * auth) moves to the next provider, a not-actionable page stops immediately. The chain is
 * visible — the status records which provider served (or failed) each page. Nothing falls
 * back silently.
 */
class PagePipeline(
    private val selector: ProviderSelector,
    private val stages: VisionStageRegistry,
    private val codec: PageCodec,
    private val store: PageStore,
    private val context: ContextStore,
    private val status: StatusHub,
    private val configSource: ConfigSource,
    private val budgets: StageBudgets = StageBudgets.DEFAULT,
    private val lanes: ProviderLanes = ProviderLanes(),
) {

    /**
     * Bridges model requirements to on-disk files. The download manager wires this at
     * construction; the pipeline itself never knows where models live.
     */
    var modelResolver: (ModelRequirement) -> ModelFiles = {
        throw MtlException.ModelMissing(it.files)
    }

    /** Runs one page to completion. Never throws — every failure is a [PageOutcome.Failed]. */
    suspend fun run(work: PageWork): PageOutcome {
        val config = configSource.current()
        val (mangaId, chapterId) = work.key

        // Size gate first: nearly free, and a corrupt page is never in the cache anyway.
        PageDecision.sizeRejection(work.imageBytes)?.let { reason ->
            return fail(work, reason, PageOutcome.MtlError.NOT_ACTIONABLE)
        }

        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.PREPARING)

        // Cache identity is computed from who *would* serve (readiness ignored): a cached page
        // must be findable offline, when its provider is unreachable. Store-time identity is
        // computed from who actually served — the two differ exactly when a fallback ran,
        // which is correct: a different provider is a different translation.
        val cacheIdentity = cacheIdentityFor(config)
            ?: return fail(
                work,
                selector.unavailableReason(requirementFor(ProviderKind.TEXT_LINES, config)),
                PageOutcome.MtlError.PROVIDER_UNAVAILABLE,
            )

        // Fast path: this exact page image, translated under this exact configuration.
        val contentKey = CacheKeys.forContent(work.imageBytes, cacheIdentity)
        val savedKey = CacheKeys.forSavedPage(mangaId, chapterId, work.pageIndex, work.imageBytes, cacheIdentity)
        store.lookupSavedPage(savedKey)?.let { bytes ->
            status.pageCached(mangaId, chapterId, work.pageIndex)
            return PageOutcome.Cached(bytes)
        }
        store.lookupContent(contentKey)?.let { bytes ->
            store.storeSavedPage(savedKey, bytes, sidecarTitle(mangaId))
            status.pageCached(mangaId, chapterId, work.pageIndex)
            return PageOutcome.Cached(bytes)
        }

        // Decode under the pipeline's budget; a decode failure is a page problem, not a
        // provider problem, so it fails the page without walking any chain.
        val bounds = codec.boundsOf(work.imageBytes)
        when (val check = PageDecision.decide(work.imageBytes, bounds, config.longPageSlicingEnabled)) {
            is PageCheck.Rejected -> return fail(work, check.reason, PageOutcome.MtlError.NOT_ACTIONABLE)
            is PageCheck.Ok -> {
                val image = withTimeoutOrNull(budgets.prepareMs) {
                    codec.decode(work.imageBytes, check.sampleSize)
                }
                if (image == null) {
                    return fail(work, "Unable to decode image", PageOutcome.MtlError.NOT_ACTIONABLE)
                }
                try {
                    return translateDecoded(work, config, image)
                } finally {
                    runCatching { image.close() }
                }
            }
        }
    }

    private suspend fun translateDecoded(work: PageWork, config: MtlConfig, image: PageImage): PageOutcome {
        val (mangaId, chapterId) = work.key
        // The chain spans kinds: a text provider needs vision stages around it, a full-page
        // provider does not. Order preserves the selector's preference within each kind.
        val chain = selector.chain(requirementFor(ProviderKind.TEXT_LINES, config)) +
            selector.chain(requirementFor(ProviderKind.VISION_PAGE, config)) +
            selector.chain(requirementFor(ProviderKind.FULL_PAGE_IMAGE, config))

        if (chain.isEmpty()) {
            return fail(
                work,
                selector.unavailableReason(requirementFor(ProviderKind.TEXT_LINES, config)),
                PageOutcome.MtlError.PROVIDER_UNAVAILABLE,
            )
        }

        // One detect/OCR/group pass per page, computed on first need and shared by every
        // provider attempt — the old per-provider rescan paid the full vision cost again
        // for each fallback candidate.
        var scan: Scan? = null
        suspend fun scanOnce(): Scan? {
            if (scan == null) scan = visionScan(work, image)
            return scan
        }
        try {
            var lastFailure: PageOutcome.Failed? = null
            var attempted = false
            for (provider in chain) {
                attempted = true
                status.pagePhase(mangaId, chapterId, work.pageIndex, phaseFor(provider.capabilities.kind))
                val session = try {
                    provider.open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MtlException) {
                    lastFailure = failedOutcome(e, provider)
                    continue
                } catch (e: Exception) {
                    lastFailure = failedOutcome(MtlException.PipelineCrash("opening ${provider.displayName}", e), provider)
                    continue
                }
                val outcome = try {
                    lanes.withLane(provider) {
                        try {
                            serve(work, config, provider, session, image, ::scanOnce)
                        } finally {
                            runCatching { session.close() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MtlException) {
                    failedOutcome(e, provider)
                } catch (e: Exception) {
                    failedOutcome(MtlException.PipelineCrash("translating via ${provider.displayName}", e), provider)
                }
                if (outcome !is PageOutcome.Failed) return outcome
                lastFailure = outcome
                // Not-actionable pages are the page's own problem — no provider can fix them.
                if (outcome.errorKind == PageOutcome.MtlError.NOT_ACTIONABLE) return outcome
            }
            // Offline fallback: the chain was tried and failed, so the reader keeps the original
            // page, the outcome stays retryable, and nothing is cached — the old pipeline's
            // fallback instead re-typeset the source text and stored the result, poisoning the
            // cache with a non-translation.
            if (attempted && config.offlineFallback && lastFailure != null) {
                status.pageSkipped(mangaId, chapterId, work.pageIndex, "translation unavailable — showing original page")
                return PageOutcome.Skipped("translation unavailable — showing original page")
            }
            return lastFailure ?: fail(work, "No provider served this page", PageOutcome.MtlError.CRASH)
        } finally {
            scan?.textMask?.let { mask -> runCatching { mask.close() } }
        }
    }

    /** One provider attempt against a decoded page. */
    private suspend fun serve(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        session: ProviderSession,
        image: PageImage,
        scanOnce: suspend () -> Scan?,
    ): PageOutcome {
        val context = buildContext(work, config, provider)
        return try {
            when (provider.capabilities.kind) {
                ProviderKind.FULL_PAGE_IMAGE -> serveFullPage(work, config, provider, session, context)
                ProviderKind.TEXT_LINES -> serveText(work, config, provider, session, context, image, scanOnce)
                ProviderKind.VISION_PAGE -> serveVision(work, config, provider, session, context, image, scanOnce)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MtlException) {
            failedOutcome(e, provider)
        } catch (e: Exception) {
            failedOutcome(MtlException.PipelineCrash("translating via ${provider.displayName}", e), provider)
        }
    }

    // ---- capability-shaped serving ----

    private suspend fun serveFullPage(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        session: ProviderSession,
        context: TranslationContext,
    ): PageOutcome {
        val request = TranslationRequest.FullPage(
            sourceLang = config.sourceLang,
            targetLang = config.targetLang,
            context = context,
            policy = config.promptPolicy,
            imageBytes = work.imageBytes,
            imageMime = "image/*",
        )
        return when (val result = withTimeoutOrNull(budgets.fullPageMs) { session.translate(request) }) {
            null -> failedOutcome(MtlException.StageTimeout("full-page translate", budgets.fullPageMs), provider)
            is TranslationResult.Page -> finish(work, config, provider, result.imageBytes)
            is TranslationResult.NothingToTranslate -> skipped(work, result.reason)
            else -> failedOutcome(
                MtlException.QualityRejected("${provider.displayName} answered a full-page request with ${result::class.simpleName}"),
                provider,
            )
        }
    }

    private suspend fun serveText(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        session: ProviderSession,
        context: TranslationContext,
        image: PageImage,
        scanOnce: suspend () -> Scan?,
    ): PageOutcome {
        // Vision stages are the spine's business for text-shaped providers.
        val scan = scanOnce() ?: return skipped(work, "no text detected")
        val request = TranslationRequest.TextLines(
            sourceLang = config.sourceLang,
            targetLang = config.targetLang,
            context = context,
            policy = config.promptPolicy,
            lines = scan.readable.map { it.sourceText },
        )
        return when (val result = withTimeoutOrNull(budgets.translateMs) { session.translate(request) }) {
            null -> failedOutcome(MtlException.StageTimeout("translate", budgets.translateMs), provider)
            is TranslationResult.Lines -> renderRegions(work, config, provider, image, scan, result.translations)
            is TranslationResult.NothingToTranslate -> skipped(work, result.reason)
            else -> failedOutcome(
                MtlException.QualityRejected("${provider.displayName} answered a text request with ${result::class.simpleName}"),
                provider,
            )
        }
    }

    private suspend fun serveVision(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        session: ProviderSession,
        context: TranslationContext,
        image: PageImage,
        scanOnce: suspend () -> Scan?,
    ): PageOutcome {
        val scan = scanOnce() ?: return skipped(work, "no text detected")
        val request = TranslationRequest.VisionPage(
            sourceLang = config.sourceLang,
            targetLang = config.targetLang,
            context = context,
            policy = config.promptPolicy,
            image = image,
            regions = scan.readable,
        )
        return when (val result = withTimeoutOrNull(budgets.translateMs) { session.translate(request) }) {
            null -> failedOutcome(MtlException.StageTimeout("translate", budgets.translateMs), provider)
            is TranslationResult.Lines -> renderRegions(work, config, provider, image, scan, result.translations)
            is TranslationResult.NothingToTranslate -> skipped(work, result.reason)
            else -> failedOutcome(
                MtlException.QualityRejected("${provider.displayName} answered a vision request with ${result::class.simpleName}"),
                provider,
            )
        }
    }

    // ---- vision stages ----

    /**
     * One detect → OCR → group pass over the page. Returns null only when the page genuinely
     * has no readable text; missing stage providers and stage timeouts throw, because those
     * are fixable states the page job must report as failures, not as "nothing here".
     */
    private suspend fun visionScan(work: PageWork, image: PageImage): Scan? {
        val (mangaId, chapterId) = work.key
        val detect = stages.best(VisionStage.DETECT) as? DetectStageProvider
            ?: throw MtlException.ModelMissing(listOf("text detector"))
        val ocr = stages.best(VisionStage.OCR) as? OcrStageProvider
            ?: throw MtlException.ModelMissing(listOf("OCR model"))

        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.DETECTING)
        val detectHandle = detect.open(resolveModels(detect))
        val detected: DetectResult? = try {
            withTimeoutOrNull(budgets.detectMs) { detectHandle.detect(image) }
        } finally {
            try {
                detectHandle.close()
            } catch (_: Exception) { }
        }
        if (detected == null) throw MtlException.StageTimeout("detect", budgets.detectMs)
        if (detected.lines.isEmpty()) return null

        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.RECOGNIZING)
        val ocrHandle = ocr.open(resolveModels(ocr))
        val recognized: List<OcrLine>? = try {
            withTimeoutOrNull(budgets.ocrMs) { ocrHandle.recognize(image, detected.lines) }
        } finally {
            try {
                ocrHandle.close()
            } catch (_: Exception) { }
        }
        if (recognized == null) throw MtlException.StageTimeout("ocr", budgets.ocrMs)
        val readable = recognized.filter { it.text.isNotBlank() }
        if (readable.isEmpty()) return null
        return Scan(allLines = recognized, textMask = detected.textMask, readable = groupLines(readable))
    }

    /**
     * Groups OCR'd lines into translatable regions. A line whose [OcrLine.regionIndex] is
     * non-negative joins the group with that index (the stage did its own grouping — the
     * native adapter's union-find/MST); an ungrouped line (-1) stands alone, because the
     * spine never invents grouping the stage did not produce.
     */
    private fun groupLines(lines: List<OcrLine>): List<RegionGroup> {
        val grouped = lines.filter { it.regionIndex >= 0 }.groupBy { it.regionIndex }
        val regions = mutableListOf<RegionGroup>()
        for ((index, members) in grouped) {
            regions += RegionGroup(
                regionIndex = index,
                lines = members,
                sourceText = members.joinToString("") { it.text },
                left = members.minOf { it.left },
                top = members.minOf { it.top },
                right = members.maxOf { it.right },
                bottom = members.maxOf { it.bottom },
                direction = members.first().direction,
                angle = members.first().angle,
            )
        }
        for (line in lines.filter { it.regionIndex < 0 }) {
            regions += RegionGroup(
                regionIndex = line.regionIndex,
                lines = listOf(line),
                sourceText = line.text,
                left = line.left,
                top = line.top,
                right = line.right,
                bottom = line.bottom,
                direction = line.direction,
                angle = line.angle,
            )
        }
        // Stable order: by first member's position in the OCR output — keeps request order
        // deterministic and aligned with the reading order the stages produced.
        val order = lines.withIndex().associate { it.value to it.index }
        return regions.sortedBy { region -> region.lines.minOf { order.getValue(it) } }
    }

    private suspend fun renderRegions(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        image: PageImage,
        scan: Scan,
        translationsIn: List<String>,
    ): PageOutcome {
        val (mangaId, chapterId) = work.key
        // Defensive alignment: providers are responsible for alignment, but the pipeline never
        // trusts it — sizes are reconciled here and blanks fall back to the source text.
        val translations = align(translationsIn, scan.readable.map { it.sourceText })
        val readableRegions = scan.readable.mapIndexed { index, group ->
            TranslatedRegion(
                regionIndex = group.regionIndex,
                left = group.left,
                top = group.top,
                right = group.right,
                bottom = group.bottom,
                translatedText = translations[index],
                lines = group.lines,
                direction = group.direction,
                angle = group.angle,
            )
        }

        // Removal set = every detected region, matching the native pipeline: unreadable text
        // is erased too, so no raw source script survives beside the English. Blank
        // translatedText = remove, never re-typeset.
        val removalRegions = scan.allLines.groupBy { it.regionIndex }.map { (index, members) ->
            val readable = readableRegions.firstOrNull { it.regionIndex == index }
            TranslatedRegion(
                regionIndex = index,
                left = members.minOf { it.left },
                top = members.minOf { it.top },
                right = members.maxOf { it.right },
                bottom = members.maxOf { it.bottom },
                translatedText = readable?.translatedText.orEmpty(),
                lines = members,
                direction = members.first().direction,
                angle = members.first().angle,
            )
        }

        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.INPAINTING)
        val inpaint = stages.best(VisionStage.INPAINT) as? InpaintStageProvider
        if (inpaint != null) {
            val inpaintHandle = inpaint.open(resolveModels(inpaint))
            val handled = try {
                withTimeoutOrNull(budgets.inpaintMs) { inpaintHandle.inpaint(image, removalRegions, scan.textMask) }
            } finally {
                runCatching { inpaintHandle.close() }
            }
            if (handled == null) throw MtlException.StageTimeout("inpaint", budgets.inpaintMs)
        }

        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.RENDERING)
        val render = stages.best(VisionStage.RENDER) as? RenderStageProvider
            ?: throw MtlException.ModelMissing(listOf("typesetter"))
        val renderHandle = render.open(resolveModels(render))
        val rendered = try {
            withTimeoutOrNull(budgets.renderMs) { renderHandle.render(image, readableRegions) }
        } finally {
            runCatching { renderHandle.close() }
        } ?: throw MtlException.StageTimeout("render", budgets.renderMs)

        try {
            val webp = codec.encodeWebp(rendered, config.outputQuality)
                ?: return failedOutcome(MtlException.QualityRejected("encoding the translated page failed"), provider)
            return finish(work, config, provider, webp)
        } finally {
            runCatching { rendered.close() }
        }
    }

    // ---- completion ----

    private suspend fun finish(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
        translatedBytes: ByteArray,
    ): PageOutcome {
        val (mangaId, chapterId) = work.key
        status.pagePhase(mangaId, chapterId, work.pageIndex, PagePhase.STORING)

        val identity = identityOf(provider, config)
        val contentKey = CacheKeys.forContent(work.imageBytes, identity)
        val savedKey = CacheKeys.forSavedPage(mangaId, chapterId, work.pageIndex, work.imageBytes, identity)
        runCatching {
            withTimeoutOrNull(budgets.storeMs) {
                store.storeContent(contentKey, translatedBytes)
                store.storeSavedPage(savedKey, translatedBytes, sidecarTitle(mangaId))
            }
        }
        status.pageDone(mangaId, chapterId, work.pageIndex, servedBy = provider.id)
        return PageOutcome.Done(translatedBytes, provider.id)
    }

    private suspend fun buildContext(
        work: PageWork,
        config: MtlConfig,
        provider: MtlProvider,
    ): TranslationContext {
        if (!provider.capabilities.acceptsContext) return TranslationContext()
        val budget = BreadcrumbBudget.forContextLength(provider.contextLengthChars())
        return TranslationContext(
            mangaGrounding = context.mangaGrounding(work.key.mangaId).orEmpty(),
            breadcrumbs = context.breadcrumbs(work.key.mangaId, work.key.chapterId, budget),
        )
    }

    private suspend fun resolveModels(provider: VisionStageProvider): ModelFiles {
        val incomplete = provider.models.filter { !modelResolver(it).isComplete() }
        if (incomplete.isNotEmpty()) {
            throw MtlException.ModelMissing(incomplete.flatMap { it.files })
        }
        return modelResolver(provider.models.first())
    }

    private fun identityOf(provider: MtlProvider, config: MtlConfig): CacheIdentity = CacheIdentity(
        providerId = provider.id.value,
        model = provider.modelId(),
        sourceLang = config.sourceLang,
        targetLang = config.targetLang,
        promptFingerprint = config.promptPolicy.fingerprint(),
    )

    /** Cache-time identity: the value the serving provider will stamp, readiness ignored. */
    private fun cacheIdentityFor(config: MtlConfig): CacheIdentity? {
        val anyText = selector.chain(requirementFor(ProviderKind.TEXT_LINES, config), ignoreReadiness = true).firstOrNull()
            ?: selector.chain(requirementFor(ProviderKind.FULL_PAGE_IMAGE, config), ignoreReadiness = true).firstOrNull()
            ?: return null
        return identityOf(anyText, config)
    }

    private suspend fun sidecarTitle(mangaId: Long): String? =
        context.mangaGrounding(mangaId)?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun skipped(work: PageWork, reason: String): PageOutcome {
        status.pageSkipped(work.key.mangaId, work.key.chapterId, work.pageIndex, reason)
        return PageOutcome.Skipped(reason)
    }

    private fun fail(work: PageWork, error: String, kind: PageOutcome.MtlError): PageOutcome {
        status.pageFailed(work.key.mangaId, work.key.chapterId, work.pageIndex, error, kind.toStatusKind())
        return PageOutcome.Failed(error, retryable = kind != PageOutcome.MtlError.NOT_ACTIONABLE, errorKind = kind)
    }

    private fun failedOutcome(e: MtlException, provider: MtlProvider): PageOutcome.Failed {
        val kind = when (e) {
            is MtlException.ProviderUnavailable -> PageOutcome.MtlError.PROVIDER_UNAVAILABLE
            is MtlException.AuthRequired -> PageOutcome.MtlError.AUTH_REQUIRED
            is MtlException.RateLimited -> PageOutcome.MtlError.RATE_LIMITED
            is MtlException.StageTimeout -> PageOutcome.MtlError.TIMEOUT
            is MtlException.ModelMissing -> PageOutcome.MtlError.MODEL_MISSING
            is MtlException.QualityRejected -> PageOutcome.MtlError.QUALITY_REJECTED
            is MtlException.PageNotActionable -> PageOutcome.MtlError.NOT_ACTIONABLE
            is MtlException.PipelineCrash -> PageOutcome.MtlError.CRASH
            is MtlException.Cancelled -> PageOutcome.MtlError.CRASH
        }
        return PageOutcome.Failed(
            error = "${provider.displayName}: ${e.message ?: "translation failed"}",
            retryable = e.retryable,
            errorKind = kind,
        )
    }

    private fun phaseFor(kind: ProviderKind): PagePhase = when (kind) {
        ProviderKind.FULL_PAGE_IMAGE -> PagePhase.TRANSLATING
        else -> PagePhase.DETECTING
    }

    companion object {
        /**
         * Reconciles a provider's translations onto the source lines: same count passes
         * through, a short list is padded with source text, a long one truncated, blanks
         * replaced by their source line.
         */
        fun align(translations: List<String>, sources: List<String>): List<String> {
            if (translations.size == sources.size) {
                return translations.mapIndexed { i, t -> t.ifBlank { sources[i] } }
            }
            return sources.mapIndexed { i, source -> translations.getOrNull(i)?.ifBlank { source } ?: source }
        }

        private fun PageOutcome.MtlError.toStatusKind() = when (this) {
            PageOutcome.MtlError.PROVIDER_UNAVAILABLE -> StatusHub.PageOutcomeKind.PROVIDER_UNAVAILABLE
            PageOutcome.MtlError.AUTH_REQUIRED -> StatusHub.PageOutcomeKind.AUTH_REQUIRED
            PageOutcome.MtlError.RATE_LIMITED -> StatusHub.PageOutcomeKind.RATE_LIMITED
            PageOutcome.MtlError.TIMEOUT -> StatusHub.PageOutcomeKind.TIMEOUT
            PageOutcome.MtlError.MODEL_MISSING -> StatusHub.PageOutcomeKind.MODEL_MISSING
            PageOutcome.MtlError.QUALITY_REJECTED -> StatusHub.PageOutcomeKind.QUALITY_REJECTED
            PageOutcome.MtlError.NOT_ACTIONABLE -> StatusHub.PageOutcomeKind.NOT_ACTIONABLE
            PageOutcome.MtlError.CRASH -> StatusHub.PageOutcomeKind.CRASH
        }
    }
}

/**
 * One vision scan over a page: every OCR'd line (readable or not — the removal set needs
 * them all), the detector's stroke mask for the inpainter, and the readable lines grouped
 * into translatable regions. [textMask] is spine-owned and closed by the page run.
 */
private data class Scan(
    val allLines: List<OcrLine>,
    val textMask: PageImage?,
    val readable: List<RegionGroup>,
)
