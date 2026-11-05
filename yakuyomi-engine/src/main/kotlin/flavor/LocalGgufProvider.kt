package mtl.provider

import android.graphics.Bitmap
import exh.yakuyomi.LocalLlmManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import mihon.core.concurrency.AppDispatchersHolder
import mtl.core.MtlException
import mtl.core.MtlProvider
import mtl.core.PageCodec
import mtl.core.ProviderCapabilities
import mtl.core.ProviderId
import mtl.core.ProviderKind
import mtl.core.ProviderSession
import mtl.core.Readiness
import mtl.core.TranslationRequest
import mtl.core.TranslationResult
import tachiyomi.core.common.util.system.logcat

/**
 * On-device GGUF translation as a [mtl.core.MtlProvider], backed by [LocalLlmManager]
 * (the llama.cpp session, model resolution and sampling stay there — this class is the SPI
 * seam, nothing else).
 *
 * Vision-capable models (mmproj) additionally receive the page raster as context; the
 * line-by-line protocol is unchanged. The kind is [ProviderKind.VISION_PAGE] because the
 * request shape is lines + optional raster — a text-only model simply ignores the image,
 * exactly as the old `LocalLlmTranslator` did.
 *
 * Readiness is re-read from the manager on [refreshReadiness] (settings screen, download
 * events, chain-build time): the model must exist on disk and the runtime must be usable.
 * The selector reads the current value when it builds a chain — a provider that becomes
 * ready mid-chapter is picked up on the next chapter.
 */
class LocalGgufProvider(
    private val manager: LocalLlmManager,
    private val codec: PageCodec,
) : MtlProvider {

    override val id = ProviderId("local")
    override val displayName = "On-device GGUF"

    override val capabilities = ProviderCapabilities(
        kind = ProviderKind.VISION_PAGE,
        maxConcurrency = 1,
        costTier = 0,
        latencyTier = 3,
    )

    override val readiness = MutableStateFlow<Readiness>(Readiness.Unavailable("not_checked", "Not checked yet"))

    override fun modelId(): String = manager.resolveModel()?.id ?: "none"

    /**
     * Re-reads the manager's model/runtime state into [readiness]. Cheap (no I/O beyond the
     * model file existence check) — safe to call per chain build.
     */
    override fun refreshReadiness() {
        readiness.value = when {
            !manager.isRuntimeAvailable() ->
                Readiness.Unavailable("runtime_unavailable", "On-device runtime is not usable on this device")
            !manager.isModelReady() ->
                Readiness.Unavailable(
                    "model_not_downloaded",
                    "Local model not downloaded — open Settings → Translation → On-device LLM",
                )
            else -> Readiness.Ready
        }
    }

    override suspend fun open(): ProviderSession = LocalSession()

    private inner class LocalSession : ProviderSession {

        override suspend fun translate(request: TranslationRequest): TranslationResult =
            withContext(AppDispatchersHolder.get().io) {
                refreshReadiness()
                val ready = readiness.value
                if (ready !is Readiness.Ready) {
                    val message = (ready as? Readiness.Unavailable)?.userMessage ?: "local provider unavailable"
                    throw MtlException.ProviderUnavailable(id, message)
                }

                val lines = when (request) {
                    is TranslationRequest.TextLines -> request.lines
                    is TranslationRequest.VisionPage -> request.regions.map { it.sourceText }
                    is TranslationRequest.FullPage -> throw MtlException.QualityRejected("local GGUF cannot serve full-page requests")
                }
                if (lines.isEmpty()) return@withContext TranslationResult.NothingToTranslate("no lines")

                val pageImage = (request as? TranslationRequest.VisionPage)?.image
                val imageBytes = pageImage?.let { image ->
                    val bitmap = image.platform as? Bitmap
                    if (bitmap == null) {
                        logcat { "local GGUF: page image is not a Bitmap; dropping vision context" }
                        null
                    } else {
                        codec.encodeJpeg(image, JPEG_QUALITY)
                    }
                }

                val isEnFix = request.sourceLang.equals("EN", true) && request.targetLang.equals("EN", true)
                val prompt = buildTranslationPrompt(
                    texts = lines,
                    sourceLang = request.sourceLang,
                    targetLang = request.targetLang,
                    breadcrumb = request.context.breadcrumbs,
                    isEnFix = isEnFix,
                    mangaContext = request.context.mangaGrounding,
                    policy = request.policy,
                    imageContext = if (imageBytes != null) VISION_CONTEXT_HINT else "",
                )

                val raw = try {
                    manager.generate(prompt, imageBytes)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw MtlException.ProviderUnavailable(id, "Local LLM generation failed: ${e.message}")
                }
                    ?: throw MtlException.QualityRejected("Local LLM returned no usable translation (is the model downloaded?)")

                val parsed = parseTranslationLines(raw)
                    ?: throw MtlException.QualityRejected("Local LLM returned no usable translation (is the model downloaded?)")
                TranslationResult.Lines(translations = alignTranslationLines(parsed, lines), raw = raw)
            }

        /** Stateless — the manager owns the llama.cpp session lifetime. */
        override fun close() { }
    }

    private companion object {
        const val JPEG_QUALITY = 80
        const val VISION_CONTEXT_HINT =
            "The attached image is the manga page being processed. Use it for context " +
                "(speakers, layout, onomatopoeia) but do not invent text. "
    }
}
