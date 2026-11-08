package upscale.core

/*
 * The spine's persisted value types. Every enum here is string-stable: the app stores
 * `name` in preferences and the spine parses with [parseOrNull], falling back to a
 * documented default. Renaming a constant silently resets users — never do it.
 */

/** How the policy may compose the plan. AUTO appends the simple resample as a fallback. */
enum class UpscaleMode {
    /** Native inference only; a failed inference serves nothing. */
    NATIVE,

    /** Bitmap resample only; no models, no inference. */
    SIMPLE,

    /** Native inference when possible, simple resample as the recorded fallback. */
    AUTO,
    ;

    companion object {
        fun parseOrNull(raw: String?): UpscaleMode? = entries.firstOrNull { it.name == raw }
    }
}

/** The native path's quality preset; decides the effective scale from the user factor. */
enum class UpscalePreset { FAST, BALANCED, HIGH }

/** Which accelerator the native path prefers. AUTO resolves per device at plan time. */
enum class BackendPref { AUTO, VULKAN, NPU, CPU }

/** The model family the native path draws from. IMPORTED covers user-supplied ONNX models. */
enum class ModelFamily { REAL_CUGAN, REAL_ESRGAN, WAIFU2X, IMPORTED }

/** The simple path's resampling kernel. BICUBIC is a two-pass approximation; see SimpleResampler. */
enum class SimpleAlgo { BICUBIC, BILINEAR, NEAREST }

/**
 * Which reader is asking. The plan is the same shape everywhere, but the WebGPU viewer is the
 * only consumer that can honor the GPU tile sampler, and the policy uses the kind to pick
 * simple-path eligibility for placeholder-scaled surfaces.
 */
enum class ReaderKind { PAGER, WEBTOON, WEBGPU }

/** Why [upscale.core.UpscaleRequest.sourceBytes] is being upscaled. */
enum class UpscaleReason { DISPLAY, PREFETCH }

/**
 * Parses a persisted enum value with a fallback, so an unknown or legacy value never crashes.
 * Returns null when [raw] is null/blank so callers can layer their own default on top.
 */
inline fun <reified T : Enum<T>> parseStable(raw: String?): T? =
    raw?.takeIf { it.isNotBlank() }?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
