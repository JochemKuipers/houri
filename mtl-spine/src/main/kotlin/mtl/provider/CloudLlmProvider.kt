package mtl.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The OpenAI-compatible cloud backends, ported from the old `YakuyomiTranslator`:
 * OpenRouter, Gemini, OpenCode Zen, NVIDIA NIM, and a user-supplied OpenAI-compatible base URL.
 *
 * Porting notes — what changed and why:
 * - The old class was constructed per page with baked-in settings; this provider is long-lived
 *   and [settings] is re-read per call, so settings changes apply without re-registration.
 * - The old per-provider offline fallback (returning source text on failure) is gone: the
 *   pipeline owns fallback policy, and a provider returning source text as a "translation"
 *   poisoned the old cache with non-translations.
 * - `TranslationException` became typed [MtlException] subtypes so the pipeline can classify
 *   failures (rate limit, auth, unreachable, garbage output) instead of string-matching.
 *
 * Vision: the endpoint+model decide the kind at construction — Gemini always sees the page
 * image; other endpoints see it when the model name carries a vision hint. Changing model
 * swaps kind, which is a settings change and rebuilds the provider (app wiring).
 *
 * Readiness: call [refreshReadiness] after settings change (the wiring bridges prefs
 * listeners); the selector reads the current [Readiness] value at chain-build time.
 */
class CloudLlmProvider(
    private val settings: () -> CloudLlmSettings?,
    private val client: OkHttpClient,
    private val codec: PageCodec,
) : MtlProvider {

    /** Endpoint family. [CUSTOM] uses [CloudLlmSettings.baseUrl]. */
    enum class Endpoint { OPENROUTER, GEMINI, OPENCODE_ZEN, NVIDIA_NIM, CUSTOM }

    data class CloudLlmSettings(
        val endpoint: Endpoint,
        val apiKey: String,
        val model: String,
        val baseUrl: String = "",
        val customHeaders: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val current: CloudLlmSettings? get() = settings()

    override val id = ProviderId("cloud-llm")
    override val displayName = "Cloud LLM"

    override val capabilities: ProviderCapabilities by lazy {
        val s = current
        ProviderCapabilities(
            kind = when {
                s == null -> ProviderKind.TEXT_LINES
                s.endpoint == Endpoint.GEMINI -> ProviderKind.VISION_PAGE
                isVisionModel(s.model) -> ProviderKind.VISION_PAGE
                else -> ProviderKind.TEXT_LINES
            },
            maxConcurrency = 2,
            costTier = 3,
            latencyTier = 2,
        )
    }

    override val readiness: MutableStateFlow<Readiness> = MutableStateFlow(Readiness.Ready)

    override fun refreshReadiness() {
        val s = current
        readiness.value = when {
            s == null || s.apiKey.isBlank() -> Readiness.Unavailable(
                "not_configured",
                "Cloud provider not configured — set the API key in Settings → Translation",
            )
            s.endpoint == Endpoint.CUSTOM && sanitizeUrl(s.baseUrl).isBlank() -> Readiness.Unavailable(
                "not_configured",
                "Custom endpoint needs a base URL",
            )
            else -> Readiness.Ready
        }
    }

    override fun modelId(): String = current?.model?.ifBlank { "default" } ?: "default"

    override suspend fun open(): ProviderSession =
        CloudSession(current ?: throw MtlException.AuthRequired(id, "Cloud provider not configured"))

    private fun isVisionModel(model: String): Boolean {
        val hints = listOf("vision", "vl", "llava", "qwen2-vl", "qwen-vl", "minicpm-v", "internvl", "pixtral")
        return hints.any { it in model.lowercase() }
    }

    private fun sanitizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed.trimEnd('/')
        return ""
    }

    private fun isPrivateHost(url: String): Boolean = try {
        val host = java.net.URI(url).host?.lowercase() ?: return true
        host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.") ||
            host.endsWith(".local") || host == "metadata.google.internal"
    } catch (_: Exception) {
        true
    }

    private inner class CloudSession(private val s: CloudLlmSettings) : ProviderSession {

        override suspend fun translate(request: TranslationRequest): TranslationResult =
            withContext(AppDispatchersHolder.get().io) {
                val lines = when (request) {
                    is TranslationRequest.TextLines -> request.lines
                    is TranslationRequest.VisionPage -> request.regions.map { it.sourceText }
                    is TranslationRequest.FullPage -> throw MtlException.QualityRejected("cloud-llm cannot serve full-page requests")
                }
                if (lines.isEmpty()) return@withContext TranslationResult.NothingToTranslate("no lines")
                if (s.apiKey.isBlank()) throw MtlException.AuthRequired(id, "Cloud API key not configured (set it in Settings → Translation)")

                val isEnFix = request.sourceLang.equals("EN", true) && request.targetLang.equals("EN", true)
                val imageContext = if (request is TranslationRequest.VisionPage) {
                    "Image context: the attached page is visual context only; do not invent or replace OCR text."
                } else {
                    ""
                }
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
                val imageBytes = (request as? TranslationRequest.VisionPage)?.let { visionRequest ->
                    visionRequest.image
                        .takeIf { capabilities.kind == ProviderKind.VISION_PAGE }
                        ?.let { image -> codec.encodeJpeg(image, quality = 90) }
                        ?.takeIf { encoded -> encoded.isNotEmpty() && encoded.size <= 8 * 1024 * 1024 }
                }

                val result = when (s.endpoint) {
                    Endpoint.GEMINI -> callGemini(prompt, imageBytes)
                    Endpoint.OPENCODE_ZEN -> callOpenAICompatible(prompt, imageBytes, "https://opencode.ai/zen/v1")
                    Endpoint.NVIDIA_NIM -> callOpenAICompatible(prompt, imageBytes, "https://integrate.api.nvidia.com/v1")
                    Endpoint.CUSTOM -> callOpenAICompatible(prompt, imageBytes, s.baseUrl)
                    Endpoint.OPENROUTER -> callOpenRouter(prompt, imageBytes)
                }
                if (result == null) {
                    throw MtlException.QualityRejected("cloud-llm ${s.endpoint.name.lowercase()} returned no usable translation")
                }
                TranslationResult.Lines(translations = alignTranslationLines(result, lines))
            }

        private fun httpError(code: Int, message: String, service: String): MtlException = when (code) {
            401, 403 -> MtlException.AuthRequired(id, "$service rejected the API key (HTTP $code)")
            429 -> MtlException.RateLimited(id)
            else -> MtlException.ProviderUnavailable(id, "$service HTTP $code: $message")
        }

        private suspend fun callOpenAICompatible(prompt: String, imageBytes: ByteArray?, baseUrl: String): List<String>? {
            val safeBase = sanitizeUrl(baseUrl)
            if (safeBase.isBlank()) throw MtlException.ProviderUnavailable(id, "Base URL not configured for this provider")
            if (isPrivateHost(safeBase)) throw MtlException.ProviderUnavailable(id, "Base URL points to private host (blocked)")
            val url = "$safeBase/chat/completions"
            val body = chatCompletionsBody(s.model, prompt, imageBytes).toString().toRequestBody("application/json".toMediaType())
            val reqBuilder = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer ${s.apiKey}")
                .header("Content-Type", "application/json")
            parseCustomHeaders(s.customHeaders)?.forEach { (k, v) ->
                if (k.lowercase() !in setOf("authorization", "host", "content-length")) {
                    reqBuilder.header(k, v)
                }
            }
            return executeWithRetry(reqBuilder.build(), "OpenAI-compatible") { txt -> parseOpenRouterResponse(txt) }
        }

        private suspend fun callOpenRouter(prompt: String, imageBytes: ByteArray?): List<String>? {
            val body = chatCompletionsBody(s.model, prompt, imageBytes).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(body)
                .header("Authorization", "Bearer ${s.apiKey}")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/PineappleTwilight/komikku-pineapple")
                .header("X-Title", "Houri")
                .build()
            return executeWithRetry(req, "OpenRouter") { txt -> parseOpenRouterResponse(txt) }
        }

        private suspend fun callGemini(prompt: String, imageBytes: ByteArray?): List<String>? {
            val raw = s.model.ifBlank { "gemini-1.5-flash" }
            val modelName = raw.substringAfterLast("/").substringBefore(":").trim()
            val geminiModel = when {
                modelName.isBlank() -> "gemini-1.5-flash"
                modelName.contains("gemma", ignoreCase = true) -> "gemini-1.5-flash"
                else -> modelName
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent?key=${s.apiKey}"
            val body = buildJsonObject {
                putJsonObject("generationConfig") {
                    // Pin the reply to a JSON array of {index,text} so the model cannot answer in
                    // prose, a dash list, or a numbered list. The looser parsers still handle every
                    // other provider, and stay as the fallback if a model ignores the constraint.
                    put("responseMimeType", "application/json")
                    putJsonObject("responseSchema") {
                        put("type", "ARRAY")
                        putJsonObject("items") {
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                putJsonObject("index") { put("type", "INTEGER") }
                                putJsonObject("text") { put("type", "STRING") }
                            }
                        }
                    }
                }
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", prompt) })
                                if (imageBytes != null) {
                                    val b64 = java.util.Base64.getEncoder().encodeToString(imageBytes)
                                    add(
                                        buildJsonObject {
                                            put(
                                                "inlineData",
                                                buildJsonObject {
                                                    put("mimeType", "image/jpeg")
                                                    put("data", b64)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).header("Content-Type", "application/json").build()
            val callClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            val resp = try {
                callClient.newCall(req).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MtlException) {
                throw e
            } catch (e: Exception) {
                throw MtlException.ProviderUnavailable(id, "Gemini call failed: ${e.message}")
            }
            return try {
                if (!resp.isSuccessful) throw httpError(resp.code, resp.message, "Gemini")
                parseGeminiResponse(resp.body.string())
            } finally {
                try {
                    resp.close()
                } catch (_: Exception) { }
            }
        }

        /** Shared OpenAI-shape request body: text, or text + image for vision models. */
        private fun chatCompletionsBody(model: String, prompt: String, imageBytes: ByteArray?) = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        if (imageBytes != null) {
                            val b64 = java.util.Base64.getEncoder().encodeToString(imageBytes)
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", prompt)
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b64") })
                                    },
                                )
                            }
                        } else {
                            put("content", prompt)
                        }
                    },
                )
            }
            put("max_tokens", 2048)
        }

        /** Two attempts with the old backoff: transient codes retry once, then the last error wins. */
        private suspend fun executeWithRetry(
            req: Request,
            service: String,
            parse: (String) -> List<String>?,
        ): List<String> {
            val callClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            var lastEx: Exception? = null
            repeat(2) { attempt ->
                val resp = try {
                    callClient.newCall(req).execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastEx = e
                    if (attempt == 0) delay(500)
                    return@repeat
                }
                try {
                    if (!resp.isSuccessful) {
                        val isTransient = resp.code in 429..503
                        if (isTransient && attempt == 0) {
                            delay(600L shl attempt)
                            return@repeat
                        }
                        throw httpError(resp.code, resp.message, service)
                    }
                    val txt = resp.body.string()
                    val parsed = parse(txt)
                    if (parsed != null) return parsed
                    throw MtlException.QualityRejected("$service returned no usable translation")
                } finally {
                    try {
                        resp.close()
                    } catch (_: Exception) { }
                }
            }
            throw lastEx?.let { MtlException.ProviderUnavailable(id, "$service call failed: ${it.message}") }
                ?: MtlException.ProviderUnavailable(id, "$service call failed")
        }

        private fun parseCustomHeaders(raw: String): Map<String, String>? {
            if (raw.isBlank()) return emptyMap()
            if (raw.length > 4096) return emptyMap()
            return try {
                raw.lines().take(20)
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.contains(":") && it.length < 512 }
                    .associate {
                        val idx = it.indexOf(":")
                        val k = it.substring(0, idx).trim().take(64)
                        val v = it.substring(idx + 1).trim().take(512)
                        k to v
                    }.filterKeys { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9\\-]+")) }
            } catch (_: Exception) {
                null
            }
        }

        private fun parseOpenRouterResponse(jsonStr: String): List<String>? {
            return try {
                val root = json.parseToJsonElement(jsonStr).jsonObject
                val choices = root["choices"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
                val first = choices.firstOrNull()?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
                val message = first["message"]?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
                val content = message["content"]?.jsonPrimitive?.contentOrNull ?: return parseTranslationLinesFromJson(jsonStr)
                parseTranslationLines(content) ?: parseTranslationLinesFromJson(jsonStr)
            } catch (_: Exception) {
                parseTranslationLinesFromJson(jsonStr)
            }
        }

        private fun parseGeminiResponse(jsonStr: String): List<String>? {
            return try {
                val root = json.parseToJsonElement(jsonStr).jsonObject
                val candidates = root["candidates"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
                val first = candidates.firstOrNull()?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
                val content = first["content"]?.jsonObject ?: return parseTranslationLinesFromJson(jsonStr)
                val parts = content["parts"]?.jsonArray ?: return parseTranslationLinesFromJson(jsonStr)
                val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("\n")
                // Schema-constrained first; the loose parsers stay for a model that ignores the schema
                // or a provider whose reply is not JSON at all.
                parseStructuredTranslations(text)
                    ?: parseTranslationLines(text)
                    ?: parseTranslationLinesFromJson(jsonStr)
            } catch (_: Exception) {
                parseTranslationLinesFromJson(jsonStr)
            }
        }

        /** Stateless HTTP session — nothing to release. */
        override fun close() { }
    }
}
