package upscale.core

import kotlinx.coroutines.flow.StateFlow

/** What shape of upscaling a backend performs. */
enum class UpscalerKind {
    /**
     * Consumes a page's encoded bytes and produces upscaled display bytes, once per page.
     * Participates in the policy's ordered plan.
     */
    BYTE,

    /**
     * Refines GPU tiles during rendering (the WebGPU viewer's ArtCNN sampler). Never a plan
     * step: it is a display-quality decision consulted through the controller, because a
     * per-page sampler swap drops GPU state and sampling an upscaled page is not a second
     * upscale.
     */
    RENDER_TILE,
}

/**
 * What a backend can do. [maxScale] and [formats] let the policy skip a backend the request can
 * never satisfy without paying a session load; [viewerScope] scopes RENDER_TILE backends to the
 * reader that can install them (null = any reader).
 */
data class UpscalerCapabilities(
    val kind: UpscalerKind,
    val maxScale: Float = 4f,
    /** Image format labels the backend accepts; empty = format-agnostic. */
    val formats: Set<String> = emptySet(),
    val maxInputPixels: Long = 16L * 1024 * 1024,
    /** 0 = cheapest. Used as the last tie-break when two backends both apply. */
    val costTier: Int = 2,
    val viewerScope: ReaderKind? = null,
)

/** One upscale ask. [sourceBytes] are the page's ORIGINAL encoded bytes, never a prior output. */
class UpscaleRequest(
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val sourceBytes: ByteArray,
    val reason: UpscaleReason,
    val target: UpscaleTarget,
)

/**
 * The executable form of one plan step: everything a BYTE backend needs to run, mapped from the
 * policy's step by the controller so [upscale.policy] and the backends stay decoupled.
 * [modelId] is the manifest id for native targets or `simple_<ALGO>` for the resample;
 * [nativeScale] is 0 for the resample.
 */
data class UpscaleTarget(
    val backend: BackendPref,
    val modelId: String,
    val nativeScale: Int,
    val scale: Float,
    val preset: UpscalePreset = UpscalePreset.BALANCED,
    val simpleAlgo: SimpleAlgo? = null,
) {
    val isSimple: Boolean get() = simpleAlgo != null
}

/** What a BYTE backend answered. */
sealed interface UpscaleOutcome {
    /** Upscaled display bytes, ready for the cache and the reader. */
    data class Served(
        val bytes: ByteArray,
        val backendId: String,
        val modelId: String?,
        val elapsedMs: Long,
    ) : UpscaleOutcome

    /**
     * The backend declines without failing: wrong format, animated input, near-identity scale,
     * or an input past its caps. The plan walks to the next step; nothing is recorded as an
     * error because there is nothing to fix.
     */
    data class Passed(val reason: String) : UpscaleOutcome
}

/**
 * The backend SPI. Implementations own their sessions and their serialisation (the controller
 * hands every BYTE backend one shared inference semaphore, so a session never sees two callers).
 * Throwing [UpscaleException] demotes the plan to its next step; returning [UpscaleOutcome.Passed]
 * silently moves on.
 */
interface UpscalerBackend {
    val id: String
    val kind: UpscalerKind
    val displayName: String
    val capabilities: UpscalerCapabilities
    val readiness: StateFlow<Readiness>

    /** Re-probes the device/model state that feeds [readiness]. Cheap; safe from any thread. */
    fun refreshReadiness() {}

    suspend fun process(request: UpscaleRequest): UpscaleOutcome
}
