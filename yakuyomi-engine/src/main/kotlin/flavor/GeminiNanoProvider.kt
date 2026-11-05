package mtl.provider

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.concurrency.AppDispatchersHolder
import mtl.core.MtlException
import mtl.core.MtlProvider
import mtl.core.ProviderCapabilities
import mtl.core.ProviderId
import mtl.core.ProviderKind
import mtl.core.ProviderSession
import mtl.core.Readiness
import mtl.core.TranslationRequest
import mtl.core.TranslationResult
import tachiyomi.core.common.util.system.logcat

/**
 * On-device translation via Google's Gemini Nano (AICore / ML Kit GenAI Prompt API), ported
 * from `exh.yakuyomi.GeminiNanoTranslator`.
 *
 * Priority text provider when the device supports it: the settings toggle gates the
 * *attempt* (selection policy), and [Readiness] reports whether the model is actually
 * provisioned on this device. Only the LLM stage runs on Nano — detector/OCR/inpainter stay
 * on their NCNN/ONNX engines.
 *
 * Vision: the page bitmap arrives via [mtl.core.PageImage.platform] and is included as
 * context (speakers, layout, onomatopoeia, scene) alongside the OCR'd lines. The
 * line-by-line protocol is unchanged — vision augments, never replaces, the detected text.
 *
 * checkStatus() queries AICore; the result only changes on download events, so it is cached
 * briefly (30 s) to keep per-page translation from hammering the system call. When the model
 * is downloadable but missing, the AICore-managed download is kicked off once in the
 * background; the provider becomes Ready when it completes.
 */
class GeminiNanoProvider : MtlProvider {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)

    private val model: GenerativeModel? by lazy {
        try {
            Generation.getClient()
        } catch (e: Exception) {
            logcat { "Gemini Nano client unavailable: ${e.message}" }
            null
        }
    }

    // checkStatus() returns an Int status code (FeatureStatus is an @IntDef annotation).
    @Volatile
    private var statusCode: Int? = null

    @Volatile
    private var statusError: String? = null

    @Volatile
    private var lastStatusCheckMs = 0L

    @Volatile
    private var downloadStarted = false

    private val statusTtlMs = 30_000L

    override val id = ProviderId("gemini-nano")
    override val displayName = "Gemini Nano (on-device)"

    override val capabilities = ProviderCapabilities(
        kind = ProviderKind.VISION_PAGE,
        maxConcurrency = 1,
        costTier = 0,
        latencyTier = 1,
    )

    override val readiness = MutableStateFlow<Readiness>(Readiness.Unavailable("not_checked", "Not checked yet"))

    /** Current device/model availability: FeatureStatus code or null before the first check. */
    fun statusCodeValue(): Int? = statusCode

    /** Last error from the availability check (AICore provisioning failure, etc.), or null. */
    fun statusErrorMessage(): String? = statusError

    /**
     * Re-queries AICore and updates [readiness]. Call this from the settings screen and
     * after download events; the selector reads the current value at chain-build time.
     */
    override fun refreshReadiness() {
        val now = System.currentTimeMillis()
        val cached = statusCode
        if (cached != null && now - lastStatusCheckMs < statusTtlMs) {
            readiness.value = mapStatus(cached, statusError)
            return
        }
        scope.launch { probeReadiness() }
    }

    /** The blocking probe shared by the async refresh and the suspend translation path. */
    private suspend fun probeReadiness() {
        val m = model
        if (m == null) {
            readiness.value = Readiness.Unavailable("no_client", "AICore client unavailable")
            return
        }
        val now = System.currentTimeMillis()
        try {
            val s = m.checkStatus()
            statusCode = s
            statusError = null
            lastStatusCheckMs = now
            if (s == FeatureStatus.DOWNLOADABLE || s == FeatureStatus.DOWNLOADING) {
                ensureDownloaded(m)
            }
            readiness.value = mapStatus(s, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            logcat { "Gemini Nano status check GenAiException: ${e.message}" }
            statusError = e.message
            lastStatusCheckMs = now
            statusCode = FeatureStatus.UNAVAILABLE
            readiness.value = Readiness.Unavailable("unavailable", e.message ?: "AICore status check failed")
        } catch (e: Exception) {
            logcat { "Gemini Nano status check failed: ${e.message}" }
            statusError = e.message
            lastStatusCheckMs = now
            statusCode = FeatureStatus.UNAVAILABLE
            readiness.value = Readiness.Unavailable("unavailable", e.message ?: "AICore status check failed")
        }
    }

    private fun mapStatus(code: Int?, error: String?): Readiness = when (code) {
        FeatureStatus.AVAILABLE -> Readiness.Ready
        FeatureStatus.DOWNLOADABLE -> Readiness.Unavailable("downloadable", "Model can be downloaded (downloading in background)")
        FeatureStatus.DOWNLOADING -> Readiness.Unavailable("downloading", "Model downloading")
        else -> Readiness.Unavailable("unavailable", error ?: "Not available on this device")
    }

    private fun ensureDownloaded(m: GenerativeModel) {
        if (downloadStarted) return
        downloadStarted = true
        scope.launch {
            try {
                m.download().collectLatest { s ->
                    when (s) {
                        is DownloadStatus.DownloadCompleted -> {
                            statusCode = FeatureStatus.AVAILABLE
                            readiness.value = Readiness.Ready
                            downloadStarted = false
                            logcat { "Gemini Nano download completed" }
                        }
                        is DownloadStatus.DownloadFailed -> {
                            downloadStarted = false
                            readiness.value = Readiness.Unavailable("download_failed", s.e.message ?: "Download failed")
                            logcat { "Gemini Nano download failed: ${s.e.message}" }
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadStarted = false
                logcat { "Gemini Nano download error: ${e.message}" }
            }
        }
    }

    override fun modelId(): String = "gemini-nano"

    override suspend fun open(): ProviderSession = NanoSession()

    private inner class NanoSession : ProviderSession {

        override suspend fun translate(request: TranslationRequest): TranslationResult =
            withContext(AppDispatchersHolder.get().default) {
                val lines = when (request) {
                    is TranslationRequest.TextLines -> request.lines
                    is TranslationRequest.VisionPage -> request.regions.map { it.sourceText }
                    is TranslationRequest.FullPage -> throw MtlException.QualityRejected("gemini-nano cannot serve full-page requests")
                }
                if (lines.isEmpty()) return@withContext TranslationResult.NothingToTranslate("no lines")

                val pageBitmap = (request as? TranslationRequest.VisionPage)?.image?.platform as? Bitmap
                val invalidBitmap = pageBitmap != null &&
                    (pageBitmap.isRecycled || pageBitmap.width < 16 || pageBitmap.height < 16)
                if (invalidBitmap) {
                    throw MtlException.QualityRejected("Gemini Nano was given an unusable page image")
                }

                val m = model ?: throw MtlException.ProviderUnavailable(id, "AICore client unavailable")
                probeReadiness()
                if (statusCode != FeatureStatus.AVAILABLE) {
                    throw MtlException.ProviderUnavailable(
                        id,
                        "Gemini Nano is unavailable on this device — enable a cloud provider in Settings → Translation",
                    )
                }

                val imageContext = if (pageBitmap != null) {
                    "The attached image is the manga page being processed. Use it for context " +
                        "(speakers, layout, onomatopoeia) but do not invent text. "
                } else {
                    ""
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
                    imageContext = imageContext,
                )
                val text = generateRaw(m, prompt, pageBitmap)
                    ?: throw MtlException.QualityRejected("Gemini Nano returned no usable translation")
                val parsed = parseTranslationLines(text)
                    ?: throw MtlException.QualityRejected("Gemini Nano returned no usable translation")
                TranslationResult.Lines(translations = alignTranslationLines(parsed, lines))
            }

        /** Issues the ML Kit request and returns the candidate text, or null on failure. */
        private suspend fun generateRaw(
            m: GenerativeModel,
            prompt: String,
            pageBitmap: Bitmap?,
        ): String? {
            val genRequest = if (pageBitmap != null) {
                generateContentRequest(ImagePart(pageBitmap), TextPart(prompt)) {
                    temperature = 0.3f
                    maxOutputTokens = 2048
                }
            } else {
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.3f
                    maxOutputTokens = 2048
                }
            }
            return try {
                m.generateContent(genRequest).candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "Gemini Nano request failed: ${e.message}" }
                null
            }
        }

        override fun close() { }
    }

    fun close() {
        scope.cancel()
    }
}
