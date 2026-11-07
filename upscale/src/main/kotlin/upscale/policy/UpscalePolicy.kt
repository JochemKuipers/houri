package upscale.policy

import upscale.core.BackendPref
import upscale.core.ModelFamily
import upscale.core.ReaderKind
import upscale.core.SimpleAlgo
import upscale.core.UpscaleMode
import upscale.core.UpscalePreset

/**
 * The one decision the spine makes about a page: which BYTE steps may serve it, in what order.
 *
 * The executor (the controller) walks the plan and records every demotion; readers never see a
 * backend name. Plans are cheap to build — no I/O, no session loads — so callers may ask per page.
 */
data class UpscalePlan(
    /** Ordered fallback steps. Empty when upscaling will not run for this page. */
    val steps: List<Step>,
    /** The clamped, preset-adjusted scale the native path infers at. */
    val scale: Float,
    val preset: UpscalePreset,
    /** Why the plan is empty (null when it is not). Carried so the status hub can say why. */
    val skipReason: String? = null,
) {
    sealed interface Step {
        /** Native inference on one resolved model + backend ordering. */
        data class Native(
            val backendPref: BackendPref,
            val modelId: String,
            val family: ModelFamily,
            val nativeScale: Int,
            /** "NCNN" or "ONNX" — which format the resolved model runs as. */
            val format: String,
        ) : Step

        /** Simple bitmap resample with [algo]; the last resort, present only in AUTO/SIMPLE. */
        data class Simple(val algo: SimpleAlgo) : Step
    }

    /** True when upscaling will not run for this page. */
    val isEmpty: Boolean get() = steps.isEmpty()
}

/** One installable model the catalog offers for a family/format, with its inference scale. */
data class ModelCandidate(val modelId: String, val nativeScale: Int, val format: String)

/**
 * Everything the policy needs besides the config snapshot. All probes are pre-resolved by the
 * caller (the controller) so the policy itself performs no I/O and no session loads.
 */
data class PolicyInput(
    val readerKind: ReaderKind,
    val isAnimated: Boolean,
    val sourceBytes: ByteArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** The policy's own effective scale, computed before the plan so ordering can use it. */
    val targetScale: Float,
    /** Device-level native availability, already probed. */
    val nativeAvailable: Boolean,
    val vulkanAvailable: Boolean,
    val npuAvailable: Boolean,
    /**
     * The catalog's candidates for a family + format, nearest-scale-first. The model layer
     * decides what exists; the policy decides what runs.
     */
    val modelsFor: (family: ModelFamily, format: String) -> List<ModelCandidate>,
    /** Whether a specific model's artifacts are installed and verified. */
    val modelReady: (modelId: String) -> Boolean,
)

/**
 * The spine's single decision site for upscaling: gates, ordering, and fallback composition
 * happen here and nowhere else.
 */
object UpscalePolicy {

    const val MIN_SCALE_DELTA = 0.02f
    const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    const val MAX_INPUT_PIXELS = 16L * 1024 * 1024

    /**
     * Clamps the user factor and applies the preset's adjustment. 0f means "no upscale".
     * Legacy note: kept as the single definition — the policy uses it for both the plan's scale
     * and the native-candidate ordering.
     */
    fun effectiveScale(factorRaw: Float, preset: UpscalePreset): Float {
        val safeFactor = factorRaw.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        if (safeFactor < 1f + MIN_SCALE_DELTA) return 0f
        return when (preset) {
            UpscalePreset.FAST -> 1.5f
            UpscalePreset.BALANCED -> safeFactor
            UpscalePreset.HIGH -> (safeFactor * 1.2f).coerceAtMost(4f)
        }
    }

    fun plan(config: UpscaleConfig, input: PolicyInput): UpscalePlan {
        val scale = effectiveScale(config.factor, config.preset)
        val skip = skipReason(config, input, scale)
        if (skip != null) return UpscalePlan(emptyList(), scale, config.preset, skip)

        val steps = buildList {
            when (config.mode) {
                UpscaleMode.SIMPLE -> add(UpscalePlan.Step.Simple(config.simpleAlgo))
                UpscaleMode.NATIVE, UpscaleMode.AUTO -> {
                    addAll(nativeSteps(config, input, scale))
                    if (config.mode == UpscaleMode.AUTO) {
                        add(UpscalePlan.Step.Simple(config.simpleAlgo))
                    }
                }
            }
        }
        return UpscalePlan(steps, scale, config.preset)
    }

    /** The ordered native candidates for the config's family, or an empty list when none apply. */
    private fun nativeSteps(
        config: UpscaleConfig,
        input: PolicyInput,
        scale: Float,
    ): List<UpscalePlan.Step.Native> {
        if (!input.nativeAvailable) return emptyList()

        // (backend, format) orderings, most preferred first — same shape the old engine's
        // resolveCandidates owned, now with the device probe applied before a model resolves.
        val orderings: List<Pair<BackendPref, String>> = when (config.backendPref) {
            BackendPref.AUTO -> listOf(
                BackendPref.VULKAN to "NCNN",
                BackendPref.NPU to "ONNX",
                BackendPref.CPU to "NCNN",
                BackendPref.CPU to "ONNX",
            )
            BackendPref.VULKAN -> listOf(
                BackendPref.VULKAN to "NCNN",
                BackendPref.CPU to "NCNN",
                BackendPref.CPU to "ONNX",
            )
            BackendPref.NPU -> listOf(
                BackendPref.NPU to "ONNX",
                BackendPref.CPU to "ONNX",
            )
            BackendPref.CPU -> listOf(
                BackendPref.CPU to "NCNN",
                BackendPref.CPU to "ONNX",
            )
        }
        return orderings.mapNotNull { (backend, format) ->
            val backendUsable = when (backend) {
                BackendPref.VULKAN -> input.vulkanAvailable
                BackendPref.NPU -> input.npuAvailable
                else -> true
            }
            if (!backendUsable) return@mapNotNull null
            val candidate = input.modelsFor(config.modelFamily, format)
                .firstOrNull { input.modelReady(it.modelId) }
                ?: return@mapNotNull null
            UpscalePlan.Step.Native(
                backendPref = backend,
                modelId = candidate.modelId,
                family = config.modelFamily,
                nativeScale = candidate.nativeScale,
                format = candidate.format,
            )
        }.distinctBy { "${it.backendPref.name}:${it.modelId}" }
    }

    private fun skipReason(config: UpscaleConfig, input: PolicyInput, scale: Float): String? {
        if (!config.enabled) return "upscaling disabled"
        if (input.isAnimated) return "animated image"
        if (input.sourceBytes.isEmpty()) return "no bytes"
        if (input.sourceBytes.size > MAX_INPUT_BYTES) return "bytes over cap"
        if (input.sourceWidth > 0 && input.sourceHeight > 0 &&
            input.sourceWidth.toLong() * input.sourceHeight > MAX_INPUT_PIXELS
        ) {
            return "pixels over cap"
        }
        if (scale <= 0f) return "near-identity scale"
        if (config.mode == UpscaleMode.NATIVE && !input.nativeAvailable) {
            return "no native runtime"
        }
        return null
    }
}
