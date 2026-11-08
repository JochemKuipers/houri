package upscale.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import upscale.UpscaleController
import upscale.core.Readiness
import upscale.core.UpscaleOutcome
import upscale.core.UpscaleRequest
import upscale.core.UpscalerBackend
import upscale.core.UpscalerCapabilities
import upscale.core.UpscalerKind

/**
 * Registers the WebGPU viewer's ArtCNN tile sampler with the unified backend surface so the
 * settings screen and diagnostics see it, while keeping it OUT of per-page byte plans.
 *
 * It is a RENDER_TILE backend: the reader seam consults [upscale.UpscaleController.useGpuTileSampler]
 * (a display-quality decision combining the spine config with the viewer's own preference) and
 * installs `UpscalerArtCnn` itself — swapping a per-frame GPU tile sampler from the policy's
 * plan loop would drop every generated tile and thrash the render path.
 */
@SingleIn(AppScope::class)
@Inject
class UpscaleGpuBridge(
    private val controller: UpscaleController,
) : UpscalerBackend {
    override val id: String = "artcnn-tile"
    override val kind: UpscalerKind = UpscalerKind.RENDER_TILE
    override val displayName: String = "ArtCNN (WebGPU tiles)"

    override val capabilities: UpscalerCapabilities = UpscalerCapabilities(
        kind = UpscalerKind.RENDER_TILE,
        maxScale = 2f,
        viewerScope = upscale.core.ReaderKind.WEBGPU,
        costTier = 1,
    )

    private val _readiness = MutableStateFlow<Readiness>(Readiness.Ready)
    override val readiness: StateFlow<Readiness> = _readiness.asStateFlow()

    override fun refreshReadiness() {
        _readiness.value = Readiness.readyIf(
            controller.useGpuTileSampler(),
            "tile refinement disabled (or mode is Simple)",
        )
    }

    override suspend fun process(request: UpscaleRequest): UpscaleOutcome {
        logcat(LogPriority.WARN) {
            "RENDER_TILE backend must not be executed via process(); consult useGpuTileSampler()"
        }
        return UpscaleOutcome.Passed("render-tile backend is not a byte producer")
    }
}
