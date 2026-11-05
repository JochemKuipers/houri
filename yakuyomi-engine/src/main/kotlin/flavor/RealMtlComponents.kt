package mtl.provider

import android.content.Context
import exh.yakuyomi.LocalLlmManager
import exh.yakuyomi.TranslationPreferences
import exh.yakuyomi.YakuyomiEngine
import mtl.core.DetectStageProvider
import mtl.core.InpaintStageProvider
import mtl.core.ModelFiles
import mtl.core.ModelRequirement
import mtl.core.MtlProvider
import mtl.core.OcrStageProvider
import mtl.core.PageCodec
import mtl.core.RenderStageProvider
import okhttp3.OkHttpClient

/**
 * The `mtl` flavor's component set: the four providers in the old resolver's priority order
 * (image service → Gemini Nano → local GGUF → cloud) and the four native vision stages over
 * the shared engine.
 *
 * Ichigo's persistence bridge and the cloud settings mapping live here (not in app wiring)
 * because they are provider-internal concerns reading the same preference keys the old
 * stack wrote.
 */
class RealMtlComponents(
    private val context: Context,
    private val prefs: TranslationPreferences,
    private val engine: YakuyomiEngine,
    private val localLlm: LocalLlmManager,
) : MtlComponents {

    override fun providers(codec: PageCodec, client: OkHttpClient): List<MtlProvider> {
        val ichigoStore = PrefsIchigoStore(prefs)
        return listOf(
            IchigoProvider(ichigoStore, client, codec, IchigoFingerprint(context, ichigoStore)),
            GeminiNanoProvider(),
            LocalGgufProvider(localLlm, codec),
            CloudLlmProvider(settings = { cloudSettings(prefs) }, client = client, codec = codec),
        )
    }

    override fun detectStages(): List<DetectStageProvider> =
        listOf(NativeDetectStage(engine, NATIVE_MODELS))

    override fun ocrStages(): List<OcrStageProvider> =
        listOf(NativeOcrStage(engine, NATIVE_MODELS))

    override fun inpaintStages(): List<InpaintStageProvider> =
        listOf(NativeInpaintStage(engine, NATIVE_MODELS))

    override fun renderStages(): List<RenderStageProvider> =
        listOf(NativeRenderStage(engine, NATIVE_MODELS))

    /** The native stages resolve their own models inside [YakuyomiEngine]; the answer is
     *  always "complete" and a genuinely missing model surfaces as the engine's build failure. */
    override fun modelResolver(): (ModelRequirement) -> ModelFiles = { AlwaysCompleteModelFiles }

    override fun hasProviders(): Boolean = true

    private companion object {
        val NATIVE_MODELS = listOf(
            ModelRequirement(id = "native-vision", displayName = "Native vision models", files = listOf("detector", "ocr", "inpainter"), downloadSizeBytes = 0),
        )
    }
}

/** The native stages' always-complete [ModelFiles] — see [RealMtlComponents.modelResolver]. */
private object AlwaysCompleteModelFiles : ModelFiles {
    override val requirement = ModelRequirement(id = "native", displayName = "native", files = emptyList(), downloadSizeBytes = 0)
    override fun pathFor(file: String): String = file
    override fun isComplete(): Boolean = true
}

/** Ichigo's persistence over the manga-translator preferences. */
class PrefsIchigoStore(private val prefs: TranslationPreferences) : IchigoStore {

    override fun accessToken(): String = prefs.mangaTranslatorAccessToken().get()
    override fun refreshToken(): String = prefs.mangaTranslatorRefreshToken().get()
    override fun email(): String = prefs.mangaTranslatorEmail().get()

    override fun setAccessToken(value: String) = prefs.mangaTranslatorAccessToken().set(value)
    override fun setRefreshToken(value: String) = prefs.mangaTranslatorRefreshToken().set(value)
    override fun setEmail(value: String) = prefs.mangaTranslatorEmail().set(value)

    override fun clientUuid(): String = prefs.mangaTranslatorClientUuid().get()
    override fun setClientUuid(value: String) = prefs.mangaTranslatorClientUuid().set(value)

    override fun fingerprint(): String = prefs.mangaTranslatorFingerprint().get()
    override fun setFingerprint(value: String) = prefs.mangaTranslatorFingerprint().set(value)

    override fun baseUrl(): String = prefs.mangaTranslatorBaseUrl().get()

    override fun translationModel(): String? = null
}

/** The cloud provider's settings, read per call so settings changes apply immediately. */
internal fun cloudSettings(prefs: TranslationPreferences): CloudLlmProvider.CloudLlmSettings? {
    val endpoint = when (prefs.provider().get().lowercase()) {
        "openrouter" -> CloudLlmProvider.Endpoint.OPENROUTER
        "gemini" -> CloudLlmProvider.Endpoint.GEMINI
        "opencode_zen" -> CloudLlmProvider.Endpoint.OPENCODE_ZEN
        "nvidia_nim" -> CloudLlmProvider.Endpoint.NVIDIA_NIM
        "custom_openai" -> CloudLlmProvider.Endpoint.CUSTOM
        else -> return null // a non-cloud provider is selected; this one stays unconfigured
    }
    return CloudLlmProvider.CloudLlmSettings(
        endpoint = endpoint,
        apiKey = prefs.effectiveApiKey(),
        model = prefs.effectiveModel(),
        baseUrl = prefs.customBaseUrl().get(),
        customHeaders = prefs.customHeaders().get(),
    )
}
