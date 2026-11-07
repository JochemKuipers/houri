package upscale

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import logcat.LogPriority
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import upscale.core.ReaderKind
import upscale.core.Readiness
import upscale.core.UpscaleOutcome
import upscale.core.UpscaleReason
import upscale.core.UpscaleRequest
import upscale.core.UpscaleTarget
import upscale.core.UpscalerBackend
import upscale.identity.UpscaleIdentity
import upscale.model.UpscaleModelFormat
import upscale.model.UpscaleModelManager
import upscale.native.DeviceProbe
import upscale.policy.ModelCandidate
import upscale.policy.PolicyInput
import upscale.policy.UpscaleConfig
import upscale.policy.UpscalePlan
import upscale.policy.UpscalePolicy
import upscale.sched.PrefetchScheduler
import upscale.status.UpscaleStatusHub
import upscale.store.UpscalePageCache

/**
 * The app-facing facade over the upscaling spine: gates and API shape live here, every decision
 * lives in the policy, and the truth lives in the status hub.
 *
 * Display path: cache-first per plan step, then inference; every demotion is recorded. Prefetch
 * path: the same plan run ahead of the reader, writing only to the cache. The WebGPU viewer's
 * ArtCNN tile sampler is a display-quality decision consulted through [useGpuTileSampler]; it is
 * never a per-page plan step.
 */
class UpscaleController(
    private val scope: CoroutineScope,
    private val configSource: UpscaleConfigSource,
    private val cache: UpscalePageCache,
    val models: UpscaleModelManager,
    private val probe: DeviceProbe,
    private val status: UpscaleStatusHub,
    /** BYTE backends; the simple one must carry id "simple", native ones "ncnn"/"ort". */
    byteBackends: List<UpscalerBackend>,
    /** RENDER_TILE backends (the WebGPU ArtCNN bridge); registered for status, never executed. */
    extraBackends: List<UpscalerBackend> = emptyList(),
    private val memoryGate: () -> Boolean = { true },
) {
    private val backendsById: Map<String, UpscalerBackend> =
        (byteBackends + extraBackends).associateBy { it.id }

    /** All registered backends, for diagnostics and the settings surface. */
    val backends: List<UpscalerBackend> = byteBackends + extraBackends

    private val prefetch = PrefetchScheduler(scope, memoryGate) { work ->
        executePrefetch(work)
    }

    fun config(): UpscaleConfig = configSource.current()

    fun isEnabled(): Boolean = configSource.current().enabled

    /** The one enablement gate: global switch, per-manga store, category override, new default. */
    suspend fun isEnabledForManga(mangaId: Long): Boolean {
        if (mangaId <= 0) return false
        val config = configSource.current()
        if (config.perMangaEnabled(mangaId)) return true
        configSource.overrideForManga(mangaId)?.let { return it }
        return config.defaultForNewEntries
    }

    /** Whether the WebGPU viewer's ArtCNN tile sampler is active under the current config. */
    fun useGpuTileSampler(): Boolean {
        val config = configSource.current()
        return config.enabled && config.tileRefinement && config.mode != upscale.core.UpscaleMode.SIMPLE
    }

    // ---- page upscaling (the reader seam) ----

    /**
     * Upscales one page for display; null means "show the original". Cache-first per plan step,
     * then inference; every demotion recorded in the status hub. Never throws.
     */
    suspend fun upscaleDisplayBytes(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        bytes: ByteArray?,
        readerKind: ReaderKind,
    ): ByteArray? {
        if (bytes == null || bytes.isEmpty()) return null
        if (!isEnabledForManga(mangaId)) return null
        val config = configSource.current()

        val plan = UpscalePolicy.plan(config, buildPolicyInput(config, bytes, readerKind))
        if (plan.isEmpty) {
            status.markSkipped(mangaId, chapterId, pageIndex, plan.skipReason ?: "no plan")
            return null
        }

        var lastBackend: String? = null
        for (step in plan.steps) {
            val backend = backendFor(step) ?: continue
            val modelId = modelIdOf(step)
            val cacheKey = cacheKeyFor(bytes, step, plan)
            if (config.cacheEnabled) {
                cache.getCached(cacheKey)?.let { cached ->
                    status.markDone(mangaId, chapterId, pageIndex, backend.id, modelId, 0, fromCache = true)
                    status.tryEmit(
                        UpscaleStatusHub.Event.Served(
                            mangaId, chapterId, pageIndex, backend.id, modelId, cacheKey, 0,
                            prefetched = false, backendPref = stepBackendPref(step),
                        ),
                    )
                    return cached
                }
            }
            if (backend.readiness.value is Readiness.Unavailable) {
                status.markFellBack(mangaId, chapterId, pageIndex, backend.id, nextOrLast(plan, step), "backend not ready")
                lastBackend = backend.id
                continue
            }
            status.markRunning(mangaId, chapterId, pageIndex, backend.id)
            val start = System.currentTimeMillis()
            val outcome = runCatching {
                backend.process(
                    UpscaleRequest(
                        mangaId = mangaId,
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        sourceBytes = bytes,
                        reason = UpscaleReason.DISPLAY,
                        target = step.toTarget(plan),
                    ),
                )
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                logcat(LogPriority.WARN) { "Upscale ${backend.id}/$modelId failed: ${error.message}" }
                null
            }
            val elapsed = System.currentTimeMillis() - start
            when (outcome) {
                is UpscaleOutcome.Served -> {
                    if (config.cacheEnabled) cache.putCached(cacheKey, outcome.bytes)
                    status.markDone(mangaId, chapterId, pageIndex, backend.id, outcome.modelId, elapsed, fromCache = false)
                    status.tryEmit(
                        UpscaleStatusHub.Event.Served(
                            mangaId, chapterId, pageIndex, backend.id, outcome.modelId, cacheKey, elapsed,
                            prefetched = false, backendPref = stepBackendPref(step),
                        ),
                    )
                    return outcome.bytes
                }
                is UpscaleOutcome.Passed -> {
                    status.markSkipped(mangaId, chapterId, pageIndex, outcome.reason)
                    return null
                }
                else -> {
                    status.markFellBack(mangaId, chapterId, pageIndex, backend.id, nextOrLast(plan, step), outcomeMessage(outcome))
                    lastBackend = backend.id
                }
            }
        }
        status.markFailed(mangaId, chapterId, pageIndex, lastBackend, "every step failed")
        return null
    }

    /** Deferred wrapper for readers that submit-and-await, mirroring MtlController's shape. */
    fun upscaleAsync(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        bytes: ByteArray?,
        readerKind: ReaderKind,
    ): Deferred<ByteArray?> = scope.async {
        upscaleDisplayBytes(mangaId, chapterId, pageIndex, bytes, readerKind)
    }

    // ---- prefetch ----

    /**
     * Warms the cache for the pages after [pageIndex]. [providers] maps a page index to its byte
     * source (the reader's page stream); absent entries are skipped. The scheduler dedups,
     * gates on memory, and serializes execution.
     */
    fun prefetch(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        depth: Int = configSource.current().prefetchDepth,
        providers: Map<Int, suspend () -> ByteArray?> = emptyMap(),
    ) {
        if (depth <= 0 || providers.isEmpty()) return
        if (!configSource.current().enabled) return
        for (offset in 1..depth) {
            val target = pageIndex + offset
            val provider = providers[target] ?: continue
            prefetch.submit(PrefetchScheduler.PrefetchWork(mangaId, chapterId, target, provider))
        }
    }

    fun cancelPrefetch(mangaId: Long, chapterId: Long) = prefetch.cancelChapter(mangaId, chapterId)

    private suspend fun executePrefetch(work: PrefetchScheduler.PrefetchWork) {
        val bytes = runCatching { work.bytesProvider() }.getOrNull() ?: return
        val config = configSource.current()
        val plan = UpscalePolicy.plan(config, buildPolicyInput(config, bytes, ReaderKind.PAGER))
        if (plan.isEmpty) return
        for (step in plan.steps) {
            val backend = backendFor(step) ?: continue
            val cacheKey = cacheKeyFor(bytes, step, plan)
            if (cache.getCached(cacheKey) != null) {
                status.markDone(work.mangaId, work.chapterId, work.pageIndex, backend.id, modelIdOf(step), 0, fromCache = true)
                return
            }
            if (backend.readiness.value is Readiness.Unavailable) continue
            status.markRunning(work.mangaId, work.chapterId, work.pageIndex, backend.id)
            val start = System.currentTimeMillis()
            val outcome = runCatching {
                backend.process(
                    UpscaleRequest(
                        mangaId = work.mangaId,
                        chapterId = work.chapterId,
                        pageIndex = work.pageIndex,
                        sourceBytes = bytes,
                        reason = UpscaleReason.PREFETCH,
                        target = step.toTarget(plan),
                    ),
                )
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                null
            }
            val elapsed = System.currentTimeMillis() - start
            when (outcome) {
                is UpscaleOutcome.Served -> {
                    if (config.cacheEnabled) cache.putCached(cacheKey, outcome.bytes)
                    status.markDone(work.mangaId, work.chapterId, work.pageIndex, backend.id, outcome.modelId, elapsed, fromCache = false)
                    status.tryEmit(
                        UpscaleStatusHub.Event.Served(
                            work.mangaId,
                            work.chapterId,
                            work.pageIndex,
                            backend.id,
                            outcome.modelId,
                            cacheKey,
                            elapsed,
                            prefetched = true,
                        ),
                    )
                    return
                }
                is UpscaleOutcome.Passed -> return
                else -> continue
            }
        }
    }

    // ---- diagnostics / surfaces ----

    fun planFor(bytes: ByteArray, readerKind: ReaderKind): UpscalePlan {
        val config = configSource.current()
        return UpscalePolicy.plan(config, buildPolicyInput(config, bytes, readerKind))
    }

    fun backendReadiness(backendId: String): Readiness? = backendsById[backendId]?.readiness?.value

    fun refreshReadiness() {
        probe.invalidateCache()
        backends.forEach { it.refreshReadiness() }
    }

    // ---- passthroughs ----

    fun clearCache() = cache.clear()

    fun cacheSizeBytes(): Long = cache.sizeBytes()

    fun cacheFileCount(): Int = cache.fileCount()

    fun pruneExpired() = cache.pruneExpired()

    fun clearAllSessions() {
        backends.filterIsInstance<upscale.native.InferenceBackend>().forEach { it.clearSessions() }
    }

    // ---- internals ----

    private fun buildPolicyInput(
        config: UpscaleConfig,
        bytes: ByteArray,
        readerKind: ReaderKind,
    ): PolicyInput {
        val (width, height) = upscale.native.PageBitmap.decodeBounds(bytes) ?: (0 to 0)
        val scale = UpscalePolicy.effectiveScale(config.factor, config.preset)
        return PolicyInput(
            readerKind = readerKind,
            isAnimated = runCatching { ImageUtil.isAnimatedAndSupported(bytes) }.getOrDefault(false),
            sourceBytes = bytes,
            sourceWidth = width,
            sourceHeight = height,
            targetScale = scale,
            nativeAvailable = probe.isAvailable(),
            vulkanAvailable = probe.isVulkanAvailable(),
            npuAvailable = probe.isNpuAvailable(),
            modelsFor = { family, format ->
                val modelFormat = if (format == "ONNX") UpscaleModelFormat.ONNX else UpscaleModelFormat.NCNN
                models.candidateModels(family.name, modelFormat, scale).map { (id, nativeScale) ->
                    ModelCandidate(id, nativeScale, format)
                }
            },
            modelReady = { modelId -> models.isModelReady(modelId) },
        )
    }

    private fun backendFor(step: UpscalePlan.Step): UpscalerBackend? = when (step) {
        is UpscalePlan.Step.Native -> backendsById[if (step.format == "ONNX") "ort" else "ncnn"]
        is UpscalePlan.Step.Simple -> backendsById["simple"]
    }

    private fun modelIdOf(step: UpscalePlan.Step): String? = when (step) {
        is UpscalePlan.Step.Native -> step.modelId
        is UpscalePlan.Step.Simple -> "simple_${step.algo.name}"
    }

    private fun nextOrLast(plan: UpscalePlan, step: UpscalePlan.Step): String {
        val index = plan.steps.indexOf(step)
        return plan.steps.getOrNull(index + 1)?.let { modelIdOf(it) } ?: "none"
    }

    private fun outcomeMessage(outcome: UpscaleOutcome?): String = when (outcome) {
        null -> "failed"
        is UpscaleOutcome.Passed -> outcome.reason
        else -> "served"
    }

    /** The step's accelerator preference name, for events (achievement unlocks key on it). */
    private fun stepBackendPref(step: UpscalePlan.Step): String? = when (step) {
        is UpscalePlan.Step.Native -> step.backendPref.name
        is UpscalePlan.Step.Simple -> null
    }

    private fun cacheKeyFor(bytes: ByteArray, step: UpscalePlan.Step, plan: UpscalePlan): String = when (step) {
        is UpscalePlan.Step.Native -> {
            val resolved = models.resolvedModelById(
                step.modelId,
                if (step.format == "ONNX") UpscaleModelFormat.ONNX else UpscaleModelFormat.NCNN,
            )
            UpscaleIdentity.nativeKey(
                bytes = bytes,
                factor = plan.scale,
                modelId = step.modelId,
                presetName = plan.preset.name,
                backendName = step.backendPref.name,
                formatName = step.format,
                artifactIdentities = resolved?.artifacts?.map { "${it.name}:${it.sha256.take(12)}" } ?: emptyList(),
            )
        }
        is UpscalePlan.Step.Simple -> UpscaleIdentity.simpleKey(
            bytes = bytes,
            factor = plan.scale,
            algoName = step.algo.name,
            modeName = configSource.current().mode.name,
        )
    }

    private fun UpscalePlan.Step.toTarget(plan: UpscalePlan): UpscaleTarget = when (this) {
        is UpscalePlan.Step.Native -> UpscaleTarget(
            backend = backendPref,
            modelId = modelId,
            nativeScale = nativeScale,
            scale = plan.scale,
            preset = plan.preset,
        )
        is UpscalePlan.Step.Simple -> UpscaleTarget(
            backend = upscale.core.BackendPref.CPU,
            modelId = "simple_${algo.name}",
            nativeScale = 0,
            scale = plan.scale,
            simpleAlgo = algo,
        )
    }
}
