package mtl

import mtl.core.PromptPolicy
import mtl.core.ProviderKind

/**
 * The spine's runtime configuration, as pure values.
 *
 * The preferences bridge supplies it; the spine never touches prefs directly, so every
 * decision made from configuration is testable with plain values.
 */
data class MtlConfig(
    /** Master switch. Per-manga toggles live in [perMangaEnabled]. */
    val enabled: Boolean,
    val sourceLang: String,
    val targetLang: String,
    /** Long-page slicing toggle — changes decode strategy, not provider selection. */
    val longPageSlicingEnabled: Boolean,
    /** When a provider's output is unusable: hand back the source lines instead of failing. */
    val offlineFallback: Boolean,
    /** Prompt behavior knobs, including the glossary — fingerprint covers them for the cache. */
    val promptPolicy: PromptPolicy = PromptPolicy.DEFAULT,
    /** Webp quality for output and cache writes. */
    val outputQuality: Int = 85,
    /** Per-manga translation toggle, keyed by manga id. */
    val perMangaEnabled: (Long) -> Boolean = { false },
) {
    companion object {
        val DISABLED = MtlConfig(
            enabled = false,
            sourceLang = "JA",
            targetLang = "en",
            longPageSlicingEnabled = true,
            offlineFallback = true,
        )
    }
}

/** Supplies the current [MtlConfig]. Implemented by the preferences bridge. */
fun interface ConfigSource {
    fun current(): MtlConfig
}

/** Per-stage budgets, in milliseconds. One definition, used by the page pipeline. */
data class StageBudgets(
    val prepareMs: Long = 10_000,
    val detectMs: Long = 20_000,
    val ocrMs: Long = 30_000,
    val translateMs: Long = 90_000,
    val fullPageMs: Long = 120_000,
    val inpaintMs: Long = 60_000,
    val renderMs: Long = 20_000,
    val storeMs: Long = 5_000,
) {
    companion object {
        val DEFAULT = StageBudgets()
    }
}

/** The requirement a page translation makes, derived from kind + config. */
internal fun requirementFor(kind: ProviderKind, config: MtlConfig) = mtl.select.CallRequirement(
    kind = kind,
    sourceLang = config.sourceLang,
    targetLang = config.targetLang,
    needsContext = true,
)
