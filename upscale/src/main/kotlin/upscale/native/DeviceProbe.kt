package upscale.native

import android.content.Context
import android.os.Build

/**
 * Runtime capability probe for the upscaler. Ported from `UpscaleBackendDetector`: feature flags
 * alone are not treated as proof of a working accelerator - Vulkan also requires an NCNN device,
 * and NNAPI requires the ORT provider to be present on API 29+.
 *
 * The probe answers [upscale.core.BackendPref] questions; the policy composes its plan from them.
 */
class DeviceProbe(private val context: Context) {
    data class Snapshot(
        val ncnnLoaded: Boolean,
        val ortRuntimeAvailable: Boolean,
        val vulkanDevices: Int,
        val nnapiAvailable: Boolean,
        val abi: String,
    ) {
        val computedAtMs: Long = System.currentTimeMillis()

        val nativeAvailable: Boolean
            get() = ncnnLoaded || ortRuntimeAvailable || nnapiAvailable
    }

    @Volatile
    private var cached: Snapshot? = null
    private val lock = Any()
    private val ttlMs = 30_000L

    fun isAvailable(): Boolean = snapshot().nativeAvailable

    fun isVulkanAvailable(): Boolean = snapshot().vulkanDevices > 0

    fun isNpuAvailable(): Boolean = snapshot().nnapiAvailable

    fun isOrtRuntimeAvailable(): Boolean = snapshot().ortRuntimeAvailable || snapshot().nnapiAvailable

    fun isBackendAvailable(backend: upscale.core.BackendPref): Boolean = when (backend) {
        upscale.core.BackendPref.AUTO -> true
        upscale.core.BackendPref.VULKAN -> isVulkanAvailable()
        upscale.core.BackendPref.NPU -> isNpuAvailable()
        upscale.core.BackendPref.CPU -> snapshot().ncnnLoaded || isOrtRuntimeAvailable()
    }

    fun availableBackends(): List<upscale.core.BackendPref> = buildList {
        add(upscale.core.BackendPref.AUTO)
        if (isVulkanAvailable()) add(upscale.core.BackendPref.VULKAN)
        if (isNpuAvailable()) add(upscale.core.BackendPref.NPU)
        if (isBackendAvailable(upscale.core.BackendPref.CPU)) add(upscale.core.BackendPref.CPU)
    }

    fun effectiveBackend(requested: upscale.core.BackendPref): upscale.core.BackendPref {
        if (requested != upscale.core.BackendPref.AUTO && isBackendAvailable(requested)) return requested
        return when {
            isVulkanAvailable() -> upscale.core.BackendPref.VULKAN
            isNpuAvailable() -> upscale.core.BackendPref.NPU
            else -> upscale.core.BackendPref.CPU
        }
    }

    fun invalidateCache() {
        synchronized(lock) { cached = null }
    }

    fun diagnostics(): String {
        val value = snapshot()
        return "ncnn=${value.ncnnLoaded} ort=${value.ortRuntimeAvailable} vulkan=${value.vulkanDevices} nnapi=${value.nnapiAvailable} abi=${Build.SUPPORTED_ABIS.joinToString()}"
    }

    private fun snapshot(): Snapshot {
        val current = cached
        val now = System.currentTimeMillis()
        if (current != null && now - current.computedAtMs < ttlMs) return current
        return synchronized(lock) {
            val second = cached
            if (second != null && System.currentTimeMillis() - second.computedAtMs < ttlMs) return@synchronized second
            val loaded = runCatching { NcnnUpscaler.isLoaded() }.getOrDefault(false)
            val gpuCount = if (loaded && hasVulkanFeature()) {
                runCatching { NcnnUpscaler.gpuCount() }.getOrDefault(0)
            } else {
                0
            }
            val ortRuntime = runCatching { OrtUpscaleSession.isOrtRuntimeAvailable() }.getOrDefault(false)
            val nnapi = ortRuntime && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
                OrtUpscaleSession.isRuntimeAvailable()
            }.getOrDefault(false)
            Snapshot(
                ncnnLoaded = loaded,
                ortRuntimeAvailable = ortRuntime,
                vulkanDevices = gpuCount.coerceAtLeast(0),
                nnapiAvailable = nnapi,
                abi = Build.SUPPORTED_ABIS.joinToString(),
            ).also { cached = it }
        }
    }

    private fun hasVulkanFeature(): Boolean = runCatching {
        context.packageManager.hasSystemFeature("android.hardware.vulkan.version")
    }.getOrDefault(false)
}
