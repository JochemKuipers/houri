package upscale.policy

import upscale.core.BackendPref
import upscale.core.ModelFamily
import upscale.core.SimpleAlgo
import upscale.core.UpscaleMode
import upscale.core.UpscalePreset

/**
 * One immutable snapshot of everything the policy is allowed to know. The app rebuilds it from
 * preferences whenever they change; the spine never reads a pref store, so policy decisions are
 * reproducible and testable.
 *
 * The two resolver lambdas answer the override ladder (per-manga > per-category > default for
 * new entries); the app binds them to the persisted stores.
 */
data class UpscaleConfig(
    val enabled: Boolean = false,
    val mode: UpscaleMode = UpscaleMode.AUTO,
    val preset: UpscalePreset = UpscalePreset.BALANCED,
    val backendPref: BackendPref = BackendPref.AUTO,
    val modelFamily: ModelFamily = ModelFamily.REAL_CUGAN,
    /** User-facing factor; clamped to 1..4 by the policy, never trusted raw. */
    val factor: Float = 2f,
    val simpleAlgo: SimpleAlgo = SimpleAlgo.BICUBIC,
    val cacheEnabled: Boolean = true,
    /** Read-ahead depth in pages; 0 disables prefetch. */
    val prefetchDepth: Int = 2,
    /** WebGPU tile sampler refinement (ArtCNN) while the mode is not SIMPLE. */
    val tileRefinement: Boolean = true,
    /** Whether a NEW library entry defaults to opted-in (per-manga store still overrides). */
    val defaultForNewEntries: Boolean = false,
    /** Whether the manga itself is opted in (the per-manga store's stored value). */
    val perMangaEnabled: (mangaId: Long) -> Boolean = { false },
)
