package mtl.core

import kotlinx.coroutines.flow.StateFlow

/**
 * A translation backend.
 *
 * Providers are long-lived singletons that know their own configuration and warm state
 * (a loaded GGUF model, an authenticated session); [open] hands out a cheap session view over
 * that state, it does not load anything. All expensive work happens inside
 * [ProviderSession.translate], under the provider's own concurrency control.
 */
interface MtlProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities

    /** Current ability to serve a request. Recomputed by the provider when its state changes. */
    val readiness: StateFlow<Readiness>

    /**
     * The model identity stamped onto cache entries for this provider. Must be stable for a
     * given configuration — it is part of every cache key — and must change when the
     * configuration changes what a translation *is* (a different model file, a different
     * model name). Not the readiness state: this is derivable without probing.
     */
    fun modelId(): String

    /**
     * The serving model's context window in characters, when known — sizes the breadcrumb
     * budget. Null means "unknown"; the pipeline then uses its default budget.
     */
    fun contextLengthChars(): Int? = null

    /**
     * Re-probes anything expensive behind [readiness] (installed models, AICore status,
     * credentials). Providers whose readiness is cheap to maintain no-op; settings screens
     * call this through [mtl.MtlController.refreshProviderReadiness] before displaying.
     */
    fun refreshReadiness() { }

    /**
     * A session bound to the provider's current configuration. The spine closes it when the
     * configuration changes or the process scope ends; implementations may return the same
     * instance repeatedly.
     */
    suspend fun open(): ProviderSession
}

/**
 * One translation capability, opened against the provider's current configuration.
 *
 * Implementations run inside the provider's [ProviderCapabilities.maxConcurrency] lane, must
 * be safe to cancel (cancellation closes sockets / aborts generation), and must translate any
 * thrown failure into an [MtlException] — anything else escapes as [MtlException.PipelineCrash].
 */
interface ProviderSession : AutoCloseable {
    suspend fun translate(request: TranslationRequest): TranslationResult
}
