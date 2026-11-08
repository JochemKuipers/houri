package upscale.core

/**
 * Whether a backend (or the whole native path) can serve work right now.
 *
 * Feature flags alone are not readiness: the probe-backed implementations answer from what the
 * device actually loaded, and [Unavailable] carries the reason the settings UI and the status
 * hub show instead of a silent no-op.
 */
sealed interface Readiness {
    data object Ready : Readiness

    data class Unavailable(
        val reason: String,
        /** A user-facing summary; may reference settings the reader can act on. */
        val userMessage: String = reason,
    ) : Readiness

    val isReady: Boolean
        get() = this is Ready

    companion object {
        fun readyIf(condition: Boolean, reason: String): Readiness =
            if (condition) Ready else Unavailable(reason)
    }
}

/** A backend that failed to serve work always explains itself through one of these. */
sealed class UpscaleException(message: String) : Exception(message) {
    /** The backend is not usable on this device/build; the plan should not have contained it. */
    class BackendUnavailable(backendId: String, reason: String) :
        UpscaleException("backend $backendId unavailable: $reason")

    /** Inference ran and failed; the plan walks to the next step. */
    class InferenceFailed(backendId: String, reason: String) :
        UpscaleException("inference failed on $backendId: $reason")

    /** The model files this backend needs are not installed (or failed verification). */
    class ModelMissing(models: List<String>) :
        UpscaleException("models missing: ${models.joinToString()}")

    /** The input cannot be upscaled by this backend (too large, unsupported, corrupt). */
    class InputRejected(backendId: String, reason: String) :
        UpscaleException("$backendId rejected input: $reason")
}
