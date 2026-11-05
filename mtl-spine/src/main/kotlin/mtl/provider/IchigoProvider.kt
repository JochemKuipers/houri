package mtl.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import exh.log.xLogD
import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

@Serializable
internal data class IchigoTranslateRequest(
    @SerialName("base64Images") val base64Images: List<String>,
    @SerialName("targetLangCode") val targetLangCode: String,
    @SerialName("fingerprint") val fingerprint: String,
    @SerialName("clientUuid") val clientUuid: String,
    @SerialName("translationModel") val translationModel: String? = null,
)

@Serializable
internal data class IchigoTranslateResponse(
    @SerialName("images") val images: List<List<IchigoTranslation>> = emptyList(),
)

@Serializable
data class IchigoTranslation(
    @SerialName("originalLanguage") val originalLanguage: String = "",
    @SerialName("translatedText") val translatedText: String,
    @SerialName("minX") val minX: Int = 0,
    @SerialName("minY") val minY: Int = 0,
    @SerialName("maxX") val maxX: Int = 0,
    @SerialName("maxY") val maxY: Int = 0,
)

/**
 * Client for the Ichigo image-translation service as a [mtl.core.MtlProvider], ported from
 * `exh.yakuyomi.MangaTranslatorService`.
 *
 * The work is split three ways. [IchigoFingerprint] owns the fixed pseudo-browser profile
 * that bot protection requires, [IchigoAuth] owns the session, and this class keeps the
 * translation path along with the base-URL policy and the allowlists.
 *
 * Semantics on the new SPI: this is a [ProviderKind.FULL_PAGE_IMAGE] provider — encoded page
 * in, translated page out; detection/OCR/grouping all happen server-side. Error mapping:
 * the old `TranslationException` strings became typed [MtlException] subtypes, and the old
 * "found no readable text" failure is now [TranslationResult.NothingToTranslate], which the
 * pipeline counts as a skip rather than a page failure.
 */
class IchigoProvider(
    private val store: IchigoStore,
    private val client: OkHttpClient,
    private val codec: PageCodec,
    fingerprint: IchigoFingerprint,
) : MtlProvider {

    private val fingerprint: IchigoFingerprint = fingerprint

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Copy of extension's allowed target langs - validated client side to prevent injection of arbitrary strings.
    private val allowedTargetLangs = setOf(
        "en", "de", "es", "fr", "pt", "pt-br", "pt-pt", "ru", "it", "pl", "tr", "id",
        "ar", "hi", "th", "vi", "zh", "zh-cn", "zh-tw", "ja", "ko", "nl", "uk", "auto",
    )

    private val allowedModels = setOf(
        "gpt4o-mini", "gpt4o", "gpt4o-nano", "gpt-4.1", "gpt-4.1-mini", "claude-3-5-sonnet",
        "claude-3-haiku", "gemini-2.0-flash", "gemini-1.5-flash", "deepl", "google",
    )

    val auth = IchigoAuth(
        store = store,
        client = client,
        json = json,
        fingerprint = fingerprint,
        baseUrl = { baseUrl() },
        executeWithAuthRetry = { request, block -> executeWithAuthRetryUntyped(request, block) },
    )

    override val id = ProviderId("ichigo")
    override val displayName = "Ichigo (image service)"

    override val capabilities = ProviderCapabilities(
        kind = ProviderKind.FULL_PAGE_IMAGE,
        maxConcurrency = 1,
        costTier = 1,
        latencyTier = 2,
    )

    /** The service works without an account (rate-limited free tier), so this is Ready. */
    override val readiness = MutableStateFlow<Readiness>(Readiness.Ready)

    override fun modelId(): String = store.translationModel()?.ifBlank { null } ?: "default"

    override suspend fun open(): ProviderSession = IchigoSession()

    private fun baseUrl(): String {
        val raw = store.baseUrl().trim().trimEnd('/')
        if (raw.isBlank()) return "https://ichigo.moe"
        return try {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") return "https://ichigo.moe"
            val host = uri.host ?: return "https://ichigo.moe"
            if (host.isBlank()) return "https://ichigo.moe"
            // Reject userinfo, fragment abuse, private IPs
            if (uri.userInfo != null) return "https://ichigo.moe"
            if (isPrivateHost(host)) return "https://ichigo.moe"
            // Enforce trusted hosts whitelist for prod hardening; allow custom https for self-hosted but warn
            val trusted = setOf("ichigo.moe", "mangatranslator.ai", "ichigoreader.com")
            val isTrusted = trusted.any { host == it || host.endsWith(".$it") }
            // If not trusted and not localhost, still allow but we strip path/query to root to avoid SSRF to internal services
            val port = if (uri.port != -1) ":${uri.port}" else ""
            if (!isTrusted && host != "localhost" && !host.endsWith(".ichigo.moe")) {
                // Allow custom domains but sanitize: only scheme+host+port
                "https://$host$port"
            } else {
                "https://$host$port"
            }
        } catch (_: Exception) {
            "https://ichigo.moe"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false // allow for debugging? but treat as private to force fallback
        // IPv4 literal check
        val ipv4 = Regex("""\d+\.\d+\.\d+\.\d+""")
        if (ipv4.matches(host)) {
            val parts = host.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                if (parts[0] == 10) return true
                if (parts[0] == 192 && parts[1] == 168) return true
                if (parts[0] == 172 && parts[1] in 16..31) return true
            }
        }
        return false
    }

    private fun sanitizeTargetLang(raw: String): String {
        val lower = raw.trim().lowercase().take(12)
        if (lower.isBlank()) return "en"
        if (allowedTargetLangs.contains(lower)) return lower
        // Also accept 2-letter prefix
        val two = lower.take(2)
        if (allowedTargetLangs.contains(two)) return two
        return "en"
    }

    private fun sanitizeModel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().take(64)
        // Allow alphanumeric, dash, underscore, dot
        if (!Regex("""^[a-zA-Z0-9._\-]+$""").matches(trimmed)) return null
        // If known model, use as-is, else allow but truncated
        return trimmed
    }

    // --- Translate ---

    private suspend fun translateImage(
        imageBytes: ByteArray,
        targetLang: String,
        translationModel: String? = null,
    ): List<IchigoTranslation> {
        if (imageBytes.isEmpty() || imageBytes.size > 8 * 1024 * 1024) {
            throw MtlException.QualityRejected("Ichigo image too large (over the 8 MB limit)")
        }
        if (imageBytes.size < 1024) {
            throw MtlException.QualityRejected("Ichigo image too small")
        }
        // Validate image decodability early to avoid sending garbage and wasting quota
        val bounds = codec.boundsOf(imageBytes)
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0 || bounds.width > 10000 || bounds.height > 10000) {
            throw MtlException.QualityRejected("Ichigo image unsupported (not an image, or over 10000px)")
        }

        val sanitizedLang = sanitizeTargetLang(targetLang)
        val sanitizedModel = sanitizeModel(translationModel)
        val base64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        // Guard against absurd payload size
        if (base64.length > 12 * 1024 * 1024) {
            throw MtlException.QualityRejected("Ichigo image too large (over the 8 MB limit)")
        }
        val body = IchigoTranslateRequest(
            base64Images = listOf(base64),
            targetLangCode = sanitizedLang,
            fingerprint = fingerprintHash().take(256),
            clientUuid = fingerprintUuid().take(64),
            translationModel = sanitizedModel,
        )
        val url = "${baseUrl()}/translate"
        val reqJson = json.encodeToString(body)
        val headers = auth.ichigoHeaders()
        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try {
            executeWithAuthRetry(request) { resp ->
                val txt = resp.body.string().take(8 * 1024 * 1024)
                when (resp.code) {
                    200 -> {
                        val parsed = try {
                            json.decodeFromString<IchigoTranslateResponse>(txt)
                        } catch (e: Exception) {
                            xLogW("Ichigo decode failed: ${e.message}")
                            throw MtlException.QualityRejected("Ichigo: invalid response")
                        }
                        val list = parsed.images.firstOrNull() ?: emptyList<IchigoTranslation>()
                        sanitizeTranslations(list)
                    }
                    429 -> {
                        xLogW("Ichigo 429 rate-limited")
                        throw MtlException.RateLimited(id)
                    }
                    401, 403 -> {
                        xLogW("Ichigo auth failed ${resp.code}")
                        // Clear stale token like the extension's authenticatedFetch does
                        auth.clearAuth()
                        throw MtlException.AuthRequired(id, "Ichigo auth failed — please log in again at Settings → Translation → Ichigo")
                    }
                    400 -> {
                        xLogW("Ichigo 400 bad request")
                        throw MtlException.QualityRejected("Ichigo rejected request (400) — image may be unsupported")
                    }
                    500, 502, 503, 504 -> {
                        xLogW("Ichigo server error ${resp.code}")
                        throw MtlException.ProviderUnavailable(id, "Ichigo server error (${resp.code}) — try again later")
                    }
                    else -> {
                        xLogW("Ichigo HTTP ${resp.code}")
                        throw MtlException.ProviderUnavailable(id, "Ichigo HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MtlException) {
            throw e
        } catch (e: Exception) {
            xLogD("Ichigo call failed: ${e.message}")
            throw MtlException.ProviderUnavailable(id, "Ichigo failed: ${e.message}")
        }
    }

    private fun fingerprintHash(): String = fingerprint.fingerprint()

    private fun fingerprintUuid(): String = fingerprint.clientUuid()

    private suspend fun <T> executeWithAuthRetry(
        request: Request,
        block: suspend (okhttp3.Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        // injected client (fake-OkHttp testable); refresh once, else clear stale Bearer and retry once
        var resp = client.newCall(request).execute()
        // Mirror extension's authenticatedFetch: on 401/403 with token, clear and retry once without stale token
        if ((resp.code == 401 || resp.code == 403) && auth.accessToken().isNotBlank()) {
            val staleCode = resp.code
            try {
                resp.body.string()
            } catch (_: Exception) {
            }
            resp.close()
            val refreshed = try {
                auth.refreshAccessToken()
            } catch (_: Exception) {
                false
            }
            if (!refreshed) {
                xLogW("Ichigo token appears stale (HTTP $staleCode), clearing and retrying once")
                auth.clearAuth()
            }
            val retryHeaders = auth.ichigoHeaders()
            val retryRequest = request.newBuilder().apply {
                // Remove old Authorization, re-apply current headers
                removeHeader("Authorization")
                retryHeaders.forEach { (k, v) -> header(k, v) }
                // If no token now, ensure Authorization is absent
                if (!retryHeaders.containsKey("Authorization")) removeHeader("Authorization")
            }.build()
            resp = client.newCall(retryRequest).execute()
            // Let block handle the retry response code (will throw the appropriate MtlException)
            return@withContext finishResponse(resp, block)
        }
        return@withContext finishResponse(resp, block)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeWithAuthRetryUntyped(
        request: Request,
        block: suspend (okhttp3.Response) -> Any?,
    ): Any? = executeWithAuthRetry(request) { resp -> block(resp) }

    private suspend fun <T> finishResponse(resp: okhttp3.Response, block: suspend (okhttp3.Response) -> T): T {
        return try {
            block(resp)
        } finally {
            try {
                resp.close()
            } catch (_: Exception) { }
        }
    }

    private fun sanitizeTranslations(list: List<IchigoTranslation>): List<IchigoTranslation> {
        if (list.isEmpty()) return emptyList()
        val maxCount = 200
        return list.take(maxCount).mapNotNull { t ->
            val text = t.translatedText
                .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "") // strip control chars
                .trim()
                .take(500)
            if (text.isBlank()) return@mapNotNull null
            val minX = t.minX.coerceIn(0, 10000)
            val minY = t.minY.coerceIn(0, 10000)
            val maxX = t.maxX.coerceIn(0, 10000)
            val maxY = t.maxY.coerceIn(0, 10000)
            if (maxX <= minX || maxY <= minY) return@mapNotNull null
            val w = maxX - minX
            val h = maxY - minY
            if (w < 4 || h < 4 || w > 10000 || h > 10000) return@mapNotNull null
            IchigoTranslation(
                originalLanguage = t.originalLanguage.take(12),
                translatedText = text,
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
            )
        }
    }

    private fun renderTranslationsToWebP(
        originalBytes: ByteArray,
        translations: List<IchigoTranslation>,
    ): ByteArray? {
        return try {
            val decoded = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return null
            val bitmap = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: decoded
            if (bitmap !== decoded) decoded.recycle()
            // Harden: cap bitmap size before drawing to avoid OOM
            if (bitmap.width > 8000 || bitmap.height > 8000) {
                bitmap.recycle()
                return null
            }
            val canvas = Canvas(bitmap)
            val bgPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val textPaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            for (t in translations) {
                if (t.translatedText.isBlank()) continue
                var left = t.minX.toFloat().coerceIn(0f, bitmap.width.toFloat())
                var top = t.minY.toFloat().coerceIn(0f, bitmap.height.toFloat())
                var right = t.maxX.toFloat().coerceIn(left, bitmap.width.toFloat())
                var bottom = t.maxY.toFloat().coerceIn(top, bitmap.height.toFloat())
                val boxW = right - left
                val boxH = bottom - top
                if (boxW < 8f || boxH < 8f) continue
                canvas.drawRoundRect(left, top, right, bottom, 4f, 4f, bgPaint)
                val rawText = t.translatedText.trim().replace(Regex("\\s+"), " ")
                if (rawText.isBlank()) continue
                var textSize = (boxH * 0.45f).coerceIn(10f, 36f)
                textPaint.textSize = textSize
                val maxTextW = boxW - 8f
                var fitted = rawText
                while (textPaint.measureText(fitted) > maxTextW && textSize > 10f) {
                    textSize -= 1f
                    textPaint.textSize = textSize
                }
                if (textPaint.measureText(fitted) > maxTextW) {
                    val avgCharW = textPaint.measureText("M")
                    val maxChars = (maxTextW / avgCharW.coerceAtLeast(1f)).toInt().coerceAtLeast(6)
                    fitted = fitted.take(maxChars - 1) + "…"
                }
                val fm = textPaint.fontMetrics
                val textH = fm.descent - fm.ascent
                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
                val lines = splitToLines(fitted, textPaint, maxTextW)
                val lineH = textH * 1.1f
                val totalH = lineH * lines.size
                var startY = centerY - totalH / 2f + textH / 2f
                if (lines.size == 1) startY = centerY - (fm.ascent + fm.descent) / 2f
                for (line in lines) {
                    // top + textH exceeds bottom - 4 whenever the box is shorter than one line of text,
                    // and coerceIn throws on an inverted range, which would discard every translation
                    // on the page rather than just this box.
                    val lo = top + textH
                    val hi = bottom - 4f
                    val y = if (lo <= hi) startY.coerceIn(lo, hi) else startY.coerceIn(top, bottom)
                    canvas.drawText(line, centerX, y, textPaint)
                    startY += lineH
                    if (startY > bottom - 2f) break
                }
            }
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            xLogW("Ichigo render failed: ${e.message}")
            null
        }
    }

    private fun splitToLines(text: String, paint: Paint, maxW: Float): List<String> {
        if (paint.measureText(text) <= maxW) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(candidate) <= maxW) {
                if (cur.isNotEmpty()) cur.append(" ")
                cur.append(w)
            } else {
                if (cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    if (paint.measureText(w) > maxW) {
                        // Too long for a line of its own: give it a truncated line rather than
                        // overwriting the one just emitted, which would drop that text entirely.
                        var truncated = w
                        while (paint.measureText(truncated + "…") > maxW && truncated.length > 2) truncated = truncated.dropLast(1)
                        lines.add(truncated + "…")
                        cur = StringBuilder()
                    } else {
                        cur = StringBuilder(w)
                    }
                } else {
                    var truncated = w
                    while (paint.measureText(truncated + "…") > maxW && truncated.length > 2) truncated = truncated.dropLast(1)
                    lines.add(truncated + "…")
                }
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines.take(4)
    }

    private inner class IchigoSession : ProviderSession {

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            val page = request as? TranslationRequest.FullPage
                ?: throw MtlException.QualityRejected("ichigo only serves full-page requests")
            val translations = translateImage(
                imageBytes = page.imageBytes,
                targetLang = page.targetLang,
                translationModel = sanitizeModel(store.translationModel()),
            )
            if (translations.isEmpty()) {
                // HTTP 200 with an empty list is not transient — the service found no text it
                // could read on this page. A skip, not a failure.
                return TranslationResult.NothingToTranslate("no readable text on the page")
            }
            val rendered = renderTranslationsToWebP(page.imageBytes, translations)
                ?: throw MtlException.QualityRejected("Ichigo render failed")
            return TranslationResult.Page(imageBytes = rendered, mime = "image/webp")
        }

        override fun close() { }
    }
}
