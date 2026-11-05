package mtl.core

/**
 * The failure taxonomy for the whole MTL stack.
 *
 * Every failure a page can hit is one of these; the pipeline maps them to page states and the
 * [retryable] flag is what drives the retry UI. Anything a provider or stage throws that is not
 * one of these is wrapped in [PipelineCrash] by the spine — providers must not leak arbitrary
 * exceptions through the SPI.
 */
sealed class MtlException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Whether attempting this page again could plausibly succeed. */
    abstract val retryable: Boolean

    /** The provider/stage could not run at all right now. */
    class ProviderUnavailable(val providerId: ProviderId?, reason: String) :
        MtlException("provider unavailable: $reason") {
        override val retryable: Boolean get() = true
    }

    /** Credentials are missing, expired, or rejected. */
    class AuthRequired(val providerId: ProviderId, message: String) : MtlException(message) {
        override val retryable: Boolean get() = false
    }

    /** The provider asked us to slow down. */
    class RateLimited(val providerId: ProviderId, val retryAfterMs: Long? = null) :
        MtlException("rate limited by ${providerId.value}") {
        override val retryable: Boolean get() = true
    }

    /** One stage blew its budget. [stage] names it so the UI can say which part failed. */
    class StageTimeout(val stage: String, timeoutMs: Long) :
        MtlException("$stage timed out after ${timeoutMs}ms") {
        override val retryable: Boolean get() = true
    }

    /** A model file the stage needs is not installed. */
    class ModelMissing(val models: List<String>) :
        MtlException("model not downloaded: ${models.joinToString()}") {
        override val retryable: Boolean get() = false
    }

    /** The provider answered but the output is unusable (empty, unparseable, garbled). */
    class QualityRejected(reason: String) : MtlException(reason) {
        override val retryable: Boolean get() = true
    }

    /** The page cannot be translated: corrupt, oversized, or no actionable text. */
    class PageNotActionable(reason: String) : MtlException(reason) {
        override val retryable: Boolean get() = false
    }

    /** Escape hatch for unexpected throws — always logged with its cause. */
    class PipelineCrash(reason: String, cause: Throwable) : MtlException(reason, cause) {
        override val retryable: Boolean get() = true
    }

    /** Normal cancellation — never an error state. */
    class Cancelled : MtlException("cancelled") {
        override val retryable: Boolean get() = true
    }
}
