package mtl.sched

import mtl.core.ProviderId

/**
 * What one page is doing, as the spine's page job moves through it.
 *
 * Phases are the spine's vocabulary — finer than the old per-page states, so the UI can say
 * "OCR" rather than "Translating" forever, and every phase boundary is a cancellation point.
 */
enum class PagePhase {
    QUEUED,
    PREPARING,
    DETECTING,
    RECOGNIZING,
    TRANSLATING,
    INPAINTING,
    RENDERING,
    STORING,
    DONE,
    CACHED,
    SKIPPED,
    FAILED,
}

/** Final outcomes of a page job. */
sealed interface PageOutcome {
    /** Translated image, encoded (webp), ready to display and store. */
    data class Done(val imageBytes: ByteArray, val servedBy: ProviderId) : PageOutcome

    /** Served from cache; no pipeline ran. */
    data class Cached(val imageBytes: ByteArray) : PageOutcome

    /** Nothing to translate; original page stays. */
    data class Skipped(val reason: String) : PageOutcome

    /** Failed; [retryable] drives the retry UI. */
    data class Failed(val error: String, val retryable: Boolean, val errorKind: MtlError?) : PageOutcome

    /** Machine-readable error classification for a [Failed] outcome. */
    enum class MtlError {
        PROVIDER_UNAVAILABLE,
        AUTH_REQUIRED,
        RATE_LIMITED,
        TIMEOUT,
        MODEL_MISSING,
        QUALITY_REJECTED,
        NOT_ACTIONABLE,
        CRASH,
    }
}
