package upscale.app

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import upscale.UpscaleController
import upscale.native.DeviceProbe
import upscale.native.NcnnUpscalerBackend
import upscale.native.OrtUpscaleSession
import upscale.native.OrtUpscalerBackend
import upscale.native.SimpleUpscalerBackend
import upscale.status.UpscaleStatusHub
import upscale.store.UpscalePageCache
import java.io.File

/**
 * Assembles the upscaling spine once, exactly as MtlBootstrap does for translation: the app's
 * only entries are [controller] and the graph accessors exposed next to it.
 *
 * The model manager receives an ORT probe lambda (a real session open) so user imports are
 * validated by what the runtime can actually load, and a manifest reader bound to this app's
 * assets — both keep [upscale.model.UpscaleModelManager] constructible and testable off-device.
 */
@SingleIn(AppScope::class)
@Inject
class UpscaleBootstrap(
    context: Context,
    client: OkHttpClient,
    preferences: UpscalePreferences,
    configSource: PrefsUpscaleConfigSource,
    /** Held so its event subscription starts with the app, not on first UI touch. */
    val achievementObserver: UpscaleAchievementObserver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val status = UpscaleStatusHub()

    private val cache = UpscalePageCache(context.cacheDir)

    private val probe = DeviceProbe(context)

    private val modelManager = upscale.model.UpscaleModelManager(
        manifestAssetReader = { name ->
            runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
        },
        modelsDir = File(context.filesDir, "upscale_models"),
        client = client,
        ortProbe = { path ->
            runCatching {
                OrtUpscaleSession.open(
                    modelPath = path,
                    config = OrtUpscaleSession.Config(nativeScale = 2),
                    preferNnapi = false,
                )
            }.getOrNull()
        },
    )

    val controller: UpscaleController = UpscaleController(
        scope = scope,
        configSource = configSource,
        cache = cache,
        models = modelManager,
        probe = probe,
        status = status,
        byteBackends = listOf(
            NcnnUpscalerBackend(modelManager, Semaphore(1)),
            OrtUpscalerBackend(modelManager, Semaphore(1), probe),
            SimpleUpscalerBackend(Semaphore(1)),
        ),
        memoryGate = {
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            (runtime.maxMemory() - used) > PREFETCH_HEADROOM_BYTES
        },
    )

    init {
        // Achievement hooks subscribe here rather than in their own constructor: the hub above
        // does not exist until this class's properties are initialized, and a constructor edge
        // from the observer to the hub would be a DI dependency cycle (see the observer's KDoc).
        achievementObserver.attach(status)

        // Model/artifact changes invalidate the probe's device snapshot and every cached
        // inference session — same reaction the old engine had, now at the assembly.
        modelManager.status
            .onEach { state ->
                when (state.state) {
                    upscale.model.UpscaleModelManager.State.READY,
                    upscale.model.UpscaleModelManager.State.NOT_INSTALLED,
                    upscale.model.UpscaleModelManager.State.ERROR,
                    -> {
                        probe.invalidateCache()
                        controller.refreshReadiness()
                        controller.clearAllSessions()
                    }
                    else -> {}
                }
            }
            .launchIn(scope)
    }

    companion object {
        /** Prefetch refuses to queue speculative work below this heap headroom. */
        private const val PREFETCH_HEADROOM_BYTES = 96L * 1024 * 1024
    }
}
