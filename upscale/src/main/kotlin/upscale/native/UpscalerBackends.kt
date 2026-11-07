package upscale.native

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import upscale.core.BackendPref
import upscale.core.Readiness
import upscale.core.UpscaleException
import upscale.core.UpscaleOutcome
import upscale.core.UpscaleRequest
import upscale.core.UpscaleTarget
import upscale.core.UpscalerBackend
import upscale.core.UpscalerCapabilities
import upscale.core.UpscalerKind
import upscale.model.ResolvedUpscaleModel
import upscale.model.UpscaleModelFormat
import upscale.model.UpscaleModelManager

/**
 * The decode → infer → rescale → encode flow shared by the two native backends. Ported from the
 * old `UpscaleEngine.runNative` body so the guard behavior (no-op skip, output rescale,
 * recycling) stays identical.
 */
internal object BackendOps {

    fun runInference(
        bytes: ByteArray,
        target: UpscaleTarget,
        infer: (bitmap: Bitmap, nativeWidth: Int, nativeHeight: Int) -> Bitmap?,
    ): ByteArray? {
        val bitmap = PageBitmap.decodeBitmap(bytes) ?: return null
        var nativeBitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val (targetWidth, targetHeight) = SimpleResampler.scaledDimensions(
                bitmap.width,
                bitmap.height,
                target.scale,
            )
            // A request that clamps back onto the source dimensions is a no-op, so skip the load
            // rather than infer the page only to resample it to the size it started at. Reached
            // only when a dimension or pixel cap binds: the policy already rejects factors
            // below ~1.02, so an ordinary 2x/4x request never lands here.
            if (targetWidth == bitmap.width && targetHeight == bitmap.height) return null
            val (nativeWidth, nativeHeight) = SimpleResampler.scaledDimensions(
                bitmap.width,
                bitmap.height,
                target.nativeScale.toFloat(),
            )
            val output = infer(bitmap, nativeWidth, nativeHeight) ?: return null
            nativeBitmap = output
            scaled = if (output.width == targetWidth && output.height == targetHeight) {
                output
            } else {
                Bitmap.createScaledBitmap(output, targetWidth, targetHeight, true)
            }
            PageBitmap.compressForCache(scaled)
        } finally {
            if (scaled !== null && scaled !== nativeBitmap) scaled.recycle()
            nativeBitmap?.recycle()
            bitmap.recycle()
        }
    }
}

/**
 * Shared base for the two inference backends: session caching keyed on
 * `backend:model:nativeScale`, the one-shared-inference semaphore, and probe-driven readiness.
 *
 * A session whose inference came back empty is evicted: a session that cannot run this model
 * never will (the failure is in the weights, the backend, or the driver, not the page). Left
 * cached, every later page re-pays the session load and re-runs the failing inference; dropped,
 * the next attempt pays the load once and the plan falls through to the next step.
 */
abstract class InferenceBackend(
    protected val modelManager: UpscaleModelManager,
    private val inference: Semaphore,
) : UpscalerBackend {
    final override val kind: UpscalerKind = UpscalerKind.BYTE
    final override val capabilities: UpscalerCapabilities = UpscalerCapabilities(kind = UpscalerKind.BYTE)

    protected abstract val sessionCacheKeyPrefix: String
    private val sessionLock = Any()

    override suspend fun process(request: UpscaleRequest): UpscaleOutcome {
        val target = request.target
        if (target.isSimple) throw UpscaleException.InputRejected(id, "native backend got simple target")
        val resolved = modelManager.resolvedModelById(target.modelId, format())
            ?: throw UpscaleException.ModelMissing(listOf(target.modelId))
        val sessionKey = "$sessionCacheKeyPrefix:${resolved.id}:${resolved.nativeScale}"
        val start = System.currentTimeMillis()
        val output = inference.withPermit {
            runInference(resolved, sessionKey, request)
        }
        val elapsed = System.currentTimeMillis() - start
        return when {
            output != null -> UpscaleOutcome.Served(
                bytes = output,
                backendId = id,
                modelId = resolved.id,
                elapsedMs = elapsed,
            )
            else -> throw UpscaleException.InferenceFailed(id, "inference returned nothing for ${resolved.id}")
        }
    }

    protected abstract fun format(): UpscaleModelFormat

    protected abstract fun runInference(
        resolved: ResolvedUpscaleModel,
        sessionKey: String,
        request: UpscaleRequest,
    ): ByteArray?

    protected fun withSession(key: String, create: () -> AutoCloseable?): AutoCloseable =
        synchronized(sessionLock) {
            cachedSession(key)?.let { return@synchronized it }
            val created = create() ?: throw UpscaleException.BackendUnavailable(
                id,
                "session creation failed for $key",
            )
            storeSession(key, created)
            created
        }

    protected fun evictSession(key: String, session: AutoCloseable) {
        val evicted = synchronized(sessionLock) { removeSession(key)?.let { it === session } } ?: false
        if (evicted) runCatching { session.close() }
    }

    protected abstract fun cachedSession(key: String): AutoCloseable?

    protected abstract fun storeSession(key: String, session: AutoCloseable)

    protected abstract fun removeSession(key: String): AutoCloseable?

    fun clearSessions() {
        synchronized(sessionLock) { clearSessionsLocked() }
    }

    protected abstract fun clearSessionsLocked()
}

/**
 * NCNN inference (Vulkan or CPU), via this module's JNI bridge. Session cache + evict-on-failure
 * semantics ported from the old engine.
 */
class NcnnUpscalerBackend(
    modelManager: UpscaleModelManager,
    inference: Semaphore,
) : InferenceBackend(modelManager, inference) {

    private val sessions = mutableMapOf<String, NcnnSession>()
    private val _readiness = MutableStateFlow<Readiness>(Readiness.Unavailable("probing"))
    override val readiness: StateFlow<Readiness> = _readiness.asStateFlow()
    override val id: String = "ncnn"
    override val displayName: String = "NCNN (Vulkan/CPU)"
    override val sessionCacheKeyPrefix: String = "ncnn"

    override fun format(): UpscaleModelFormat = UpscaleModelFormat.NCNN

    override fun refreshReadiness() {
        _readiness.value = Readiness.readyIf(
            NcnnUpscaler.isLoaded(),
            "NCNN runtime not available on this ABI/device",
        )
    }

    override fun cachedSession(key: String): AutoCloseable? = sessions[key]

    override fun storeSession(key: String, session: AutoCloseable) {
        (session as? NcnnSession)?.let { sessions[key] = it }
    }

    override fun removeSession(key: String): AutoCloseable? = sessions.remove(key)

    override fun clearSessionsLocked() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    override fun runInference(
        resolved: ResolvedUpscaleModel,
        sessionKey: String,
        request: UpscaleRequest,
    ): ByteArray? {
        val param = resolved.artifact("param") ?: return null
        val bin = resolved.artifact("bin") ?: return null
        val paramPath = modelManager.fileFor(param).absolutePath
        val binPath = modelManager.fileFor(bin).absolutePath
        val session = withSession(sessionKey) {
            NcnnUpscaler.create(
                paramPath = paramPath,
                binPath = binPath,
                backend = if (request.target.backend == BackendPref.VULKAN) {
                    NcnnUpscaler.Backend.VULKAN
                } else {
                    NcnnUpscaler.Backend.CPU
                },
                nativeScale = resolved.nativeScale,
                inputOrder = resolved.inputOrder,
                outputOrder = resolved.outputOrder,
            )
        }
        session as NcnnSession
        val output = BackendOps.runInference(request.sourceBytes, request.target) { bitmap, nw, nh ->
            session.process(bitmap, nw, nh, resolved.tileSize, resolved.padding)
        }
        if (output == null) evictSession(sessionKey, session)
        return output
    }
}

/** ONNX Runtime inference (NNAPI or CPU). */
class OrtUpscalerBackend(
    modelManager: UpscaleModelManager,
    inference: Semaphore,
    private val probe: DeviceProbe,
) : InferenceBackend(modelManager, inference) {

    private val sessions = mutableMapOf<String, OrtUpscaleSession>()
    private val _readiness = MutableStateFlow<Readiness>(Readiness.Unavailable("probing"))
    override val readiness: StateFlow<Readiness> = _readiness.asStateFlow()
    override val id: String = "ort"
    override val displayName: String = "ONNX Runtime (NPU/CPU)"
    override val sessionCacheKeyPrefix: String = "ort"

    override fun format(): UpscaleModelFormat = UpscaleModelFormat.ONNX

    override fun refreshReadiness() {
        _readiness.value = Readiness.readyIf(
            probe.isOrtRuntimeAvailable(),
            "ONNX Runtime not available on this device",
        )
    }

    override fun cachedSession(key: String): AutoCloseable? = sessions[key]

    override fun storeSession(key: String, session: AutoCloseable) {
        (session as? OrtUpscaleSession)?.let { sessions[key] = it }
    }

    override fun removeSession(key: String): AutoCloseable? = sessions.remove(key)

    override fun clearSessionsLocked() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    override fun runInference(
        resolved: ResolvedUpscaleModel,
        sessionKey: String,
        request: UpscaleRequest,
    ): ByteArray? {
        val model = resolved.artifact("model") ?: return null
        val modelPath = modelManager.fileFor(model).absolutePath
        val session = withSession(sessionKey) {
            OrtUpscaleSession.open(
                modelPath = modelPath,
                config = OrtUpscaleSession.Config(
                    nativeScale = resolved.nativeScale,
                    tileSize = resolved.tileSize,
                    padding = resolved.padding,
                    inputOrder = resolved.inputOrder,
                    outputOrder = resolved.outputOrder,
                ),
                preferNnapi = request.target.backend == BackendPref.NPU,
            )
        }
        session as OrtUpscaleSession
        val output = BackendOps.runInference(request.sourceBytes, request.target) { bitmap, nw, nh ->
            session.process(bitmap, nw, nh)
        }
        if (output == null) evictSession(sessionKey, session)
        return output
    }
}

/** Simple bitmap resample: no models, no inference, always ready. */
class SimpleUpscalerBackend(
    private val inference: Semaphore,
) : UpscalerBackend {
    override val id: String = "simple"
    override val kind: UpscalerKind = UpscalerKind.BYTE
    override val displayName: String = "Simple resample"
    override val capabilities: UpscalerCapabilities =
        UpscalerCapabilities(kind = UpscalerKind.BYTE, costTier = 3)
    override val readiness: StateFlow<Readiness> =
        MutableStateFlow<Readiness>(Readiness.Ready).asStateFlow()

    override suspend fun process(request: UpscaleRequest): UpscaleOutcome {
        val target = request.target
        val algo = target.simpleAlgo
            ?: throw UpscaleException.InputRejected(id, "simple backend got native target")
        if (SimpleResampler.isNearIdentityScale(target.scale)) {
            return UpscaleOutcome.Passed("near-identity scale")
        }
        val start = System.currentTimeMillis()
        val output = inference.withPermit {
            val bitmap = PageBitmap.decodeBitmap(request.sourceBytes)
                ?: return@withPermit null
            var scaled: Bitmap? = null
            try {
                if (SimpleResampler.isNearIdentityScale(target.scale)) return@withPermit null
                scaled = SimpleResampler.upscaleBitmapWithAlgo(bitmap, target.scale, algo)
                    ?: return@withPermit null
                PageBitmap.compressForCache(scaled)
            } finally {
                if (scaled !== null && scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
        }
        return when {
            output != null -> UpscaleOutcome.Served(
                bytes = output,
                backendId = id,
                modelId = target.modelId,
                elapsedMs = System.currentTimeMillis() - start,
            )
            else -> throw UpscaleException.InferenceFailed(id, "resample produced nothing")
        }
    }
}
