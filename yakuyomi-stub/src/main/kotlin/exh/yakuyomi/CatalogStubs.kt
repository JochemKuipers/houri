package exh.yakuyomi

import android.content.Context

/** Stub of [LocalLlmBackendType] for the no-MTL APK variant. */
enum class LocalLlmBackendType {
    LLAMACPP,
}

/** Stub of [LocalLlmModel] for the no-MTL APK variant. */
data class LocalLlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val paramsB: String,
    val qualityTier: Int,
    val isTranslationFinetune: Boolean,
    val supportsVision: Boolean,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val backends: Set<LocalLlmBackendType> = setOf(LocalLlmBackendType.LLAMACPP),
    val ggufRepo: String? = null,
    val ggufFile: String? = null,
    val mmprojRepo: String? = null,
    val mmprojFile: String? = null,
    val isCustom: Boolean = false,
    val contextLength: Int = 4096,
    val defaultSampling: LocalLlmSamplingConfig? = null,
)

/** Stub of [LocalLlmSamplingConfig] for the no-MTL APK variant. */
@kotlinx.serialization.Serializable
data class LocalLlmSamplingConfig(
    val temperature: Float = 0.3f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val numThreads: Int = 0,
    val gpuLayers: Int = -1,
) {
    val resolvedThreads: Int
        get() = if (numThreads <= 0) {
            (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
        } else {
            numThreads
        }
}

/** Stub of [LocalGenerateRequest] for the no-MTL APK variant. */
data class LocalGenerateRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.3f,
    val imageBytes: ByteArray? = null,
)

/** Stub of [LocalLlmAcceleratorInfo] for the no-MTL APK variant. */
data class LocalLlmAcceleratorInfo(
    val gpuBackendCompiledIn: Boolean = false,
    val gpuBackendPluginShipped: Boolean = false,
    val vulkanCompute: Boolean = false,
) {
    val gpuBackendAvailable: Boolean get() = gpuBackendCompiledIn || gpuBackendPluginShipped

    val canOffloadToGpu: Boolean get() = gpuBackendAvailable && vulkanCompute
}

/**
 * Stub of [DeviceMemory] for the no-MTL APK variant. RAM is a device property, not a variant
 * property: the WebGPU (high-quality) renderer is available in both variants, so the checks
 * behave exactly like the engine module's.
 */
object DeviceMemory {
    // KMK --> Kept so the shared tier is expressed in the same units the old gate used. The gate
    // itself compares *tiers*, not bytes - see `isMtlSupported` below - because a byte count cannot
    // see the vendor low-RAM mark or the 32-bit ABI cap, and those are exactly the cases where the
    // old number was wrong.
    // KMK <--

    /** Minimum total RAM required to run the on-device AI translation pipeline (3 GiB). */
    const val MTL_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024
    /** Minimum total RAM required to run the WebGPU (high-quality) renderer (3 GiB). */
    const val WEBGPU_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    // KMK --> The bypass store and the tier both live in `AppMemoryMonitor` now, so the engine
    // flavor and this stub cannot drift apart on what "low RAM" means. Each of these was this
    // file's own copy of the same SharedPreferences read and the same byte comparison, which is how
    // the two flavors could disagree after a user flipped the bypass.
    /** Whether the user has permanently disabled the RAM gate via the About screen easter-egg. */
    fun isRamGateDisabled(context: Context): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.isRamGateDisabled(context)

    fun setRamGateDisabled(context: Context, disabled: Boolean) {
        tachiyomi.core.common.util.system.AppMemoryMonitor.setRamGateDisabled(context, disabled)
    }

    /** Total physical RAM in bytes, or 0 when the system service is unavailable. */
    fun totalRamBytes(context: Context): Long =
        tachiyomi.core.common.util.system.AppMemoryMonitor.totalRamBytes(context)

    fun hasSufficientRam(context: Context, minBytes: Long): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.totalRamBytes(context) >= minBytes

    /** Whether this device has enough RAM for the on-device AI translation pipeline. */
    fun isMtlSupported(context: Context): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.isMtlSupported(context)

    /** Whether this device has enough RAM for the WebGPU (high-quality) renderer. */
    fun isWebGpuSupported(context: Context): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.isWebGpuSupported(context)

    /** Whether the device runs a 64-bit ABI. */
    fun is64Bit(): Boolean = android.os.Build.SUPPORTED_ABIS.any { it.contains("64") }
    // KMK <--
    fun socManufacturer(): String = "unknown"
    fun matchesSoc(soc: String, required: String): Boolean =
        soc.equals(required, ignoreCase = true)
}

/** Stub of [LocalLlmCatalog] for the no-MTL APK variant: no models available. */
object LocalLlmCatalog {
    val allModels: List<LocalLlmModel> = emptyList()

    fun byId(id: String): LocalLlmModel? = null
    fun fitForRam(models: List<LocalLlmModel> = allModels, totalRamBytes: Long): List<LocalLlmModel> = emptyList()
    fun isRuntimeBundled(): Boolean = false
    fun bestForDevice(totalRamBytes: Long): LocalLlmModel? = null
}
