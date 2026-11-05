package mtl.core

/**
 * Stable provider identity.
 *
 * The value is part of every page and metadata cache key, so it must never change for a
 * configuration that produces the same output. Renaming a provider id silently invalidates
 * every cached translation for it — treat that as a deliberate cache drop, not a rename.
 */
@JvmInline
value class ProviderId(val value: String) {
    override fun toString(): String = value
}

/**
 * What shape of work a provider accepts. This decides where in the page pipeline the provider
 * plugs in, not which engine it runs on.
 */
enum class ProviderKind {
    /** Translates already-OCR'd text lines. Gets text + context in, lines out. */
    TEXT_LINES,

    /** Sees the page raster beside the OCR lines (vision-capable LLM). */
    VISION_PAGE,

    /** Whole-page image in, finished translated page out. Bypasses the vision pipeline. */
    FULL_PAGE_IMAGE,
}

/**
 * What a provider can do, and at what cost. [sourceLangs] / [targetLangs] being empty means
 * "any language" — the common case for LLM-backed providers.
 *
 * [maxConcurrency] is the number of in-flight requests the provider accepts: 1 for anything
 * backed by a serial runtime (local GGUF, the native pipeline), more for cloud APIs.
 */
data class ProviderCapabilities(
    val kind: ProviderKind,
    val sourceLangs: Set<String> = emptySet(),
    val targetLangs: Set<String> = emptySet(),
    /** Accepts breadcrumb / glossary / manga-grounding context. */
    val acceptsContext: Boolean = true,
    val maxConcurrency: Int = 1,
    /** 0 = free local, 3 = premium cloud. Ranks candidates in auto mode. */
    val costTier: Int = 2,
    /** 0 = instant, 3 = slow. Ranks candidates in auto mode. */
    val latencyTier: Int = 2,
)

/**
 * Whether a provider can serve a request right now.
 *
 * [Unavailable.userMessage] is user-facing; [reason] is the machine-readable cause for logs.
 */
sealed interface Readiness {
    data object Ready : Readiness

    data class Unavailable(val reason: String, val userMessage: String) : Readiness {
        companion object {
            val NOT_CONFIGURED = Unavailable("not_configured", "Not configured — open Settings → Translation")
        }
    }
}
