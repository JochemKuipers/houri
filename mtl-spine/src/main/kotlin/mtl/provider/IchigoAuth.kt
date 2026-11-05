package mtl.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat

@Serializable
internal data class IchigoLoginRequest(
    val email: String,
    val password: String,
)

@Serializable
internal data class IchigoTokens(
    @SerialName("accessToken") val accessToken: String = "",
    @SerialName("refreshToken") val refreshToken: String? = null,
)

@Serializable
internal data class IchigoAuthResponse(
    val tokens: IchigoTokens? = null,
)

@Serializable
internal data class IchigoRefreshRequest(
    @SerialName("refreshToken") val refreshToken: String,
)

/** Result of an Ichigo login attempt (mirrors the upstream API's error kinds). */
enum class IchigoLoginResult {
    Success,
    BadPassword,
    UnknownEmail,
    InvalidEmail,
    RateLimited,
    Unknown,
}

/** Result of an Ichigo signup attempt. */
enum class IchigoSignupResult {
    Success,
    EmailTaken,
    InvalidEmail,
    RateLimited,
    Unknown,
}

@Serializable
data class IchigoUser(
    val email: String? = null,
    @SerialName("subscriptionTier") val subscriptionTier: String = "free",
)

/**
 * The session half of the Ichigo image service, ported from `exh.yakuyomi.MangaTranslatorAuth`:
 * login, signup, logout, token storage and refresh.
 *
 * Requests go out through [executeWithAuthRetry], which is here rather than on the caller
 * because it has to reach the stored token — the whole point is to retry once with a
 * refreshed one. The provider passes that in as a function so this class never needs to know
 * which requests it wraps.
 */
class IchigoAuth(
    private val store: IchigoStore,
    private val client: OkHttpClient,
    private val json: Json,
    private val fingerprint: IchigoFingerprint,
    private val baseUrl: () -> String,
    private val executeWithAuthRetry: suspend (Request, suspend (okhttp3.Response) -> Any?) -> Any?,
) {
    private val spoofedUa = "Mozilla/5.0 (Linux; Android 16; SM-S928U Build/BP4A.251205.006) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36"

    fun accessToken(): String = store.accessToken().trim()

    private fun refreshToken(): String = store.refreshToken().trim()

    private fun storeTokens(access: String?, refresh: String?) {
        val a = access?.trim().orEmpty()
        if (a.isNotBlank() && a.length in 16..2048) {
            store.setAccessToken(a)
        }
        val r = refresh?.trim().orEmpty()
        if (r.isNotBlank() && r.length in 8..4096) {
            store.setRefreshToken(r)
        }
    }

    suspend fun refreshAccessToken(): Boolean {
        val rt = refreshToken()
        if (rt.isBlank()) return false
        val url = "${baseUrl()}/auth/refresh"
        val body = try {
            json.encodeToString(IchigoRefreshRequest(rt))
        } catch (_: Exception) {
            return false
        }
        val authHeaders = browserAuthHeaders()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        // blocking execute() must run on IO: callers invoke from the main thread
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 200) return@withContext false
                    val txt = resp.body.string().take(4096)
                    val parsed = try {
                        json.decodeFromString<IchigoAuthResponse>(txt)
                    } catch (_: Exception) {
                        null
                    }
                    val newAccess = parsed?.tokens?.accessToken?.trim()
                    val newRefresh = parsed?.tokens?.refreshToken?.trim()
                    if (newAccess.isNullOrBlank() || newAccess.length < 16) {
                        val fallback = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                        if (fallback.isNullOrBlank()) return@withContext false
                        storeTokens(fallback, newRefresh)
                        return@withContext true
                    }
                    storeTokens(newAccess, newRefresh)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    // Full Chrome header stack for auth endpoints: a lone User-Agent is filtered by bot
    // protection, so mirror what the site's own login page sends.
    private fun browserAuthHeaders(): Map<String, String> {
        val base = baseUrl()
        return mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Content-Type" to "application/json",
            "Client-Version" to "1.0.1",
            "X-Client-Version" to "1.0.1",
            "Origin" to base,
            "Referer" to "$base/",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"152\", \"Google Chrome\";v=\"152\", \"Not-A.Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to spoofedUa,
        )
    }

    fun ichigoHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Client-Version" to "1.0.1",
            "X-Client-Version" to "1.0.1",
            "User-Agent" to spoofedUa,
        )
        val token = accessToken()
        if (token.isNotBlank() && token.length in 16..2048) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    // --- Auth: mirrors the extension's ichigoApi.ts (login / signup / logout / metrics) ---

    suspend fun login(email: String, password: String): IchigoLoginResult {
        val e = email.trim().take(254)
        val p = password.take(512)
        if (e.isBlank() || !e.contains("@") || e.length < 5) return IchigoLoginResult.InvalidEmail
        if (p.isBlank() || p.length < 6) return IchigoLoginResult.BadPassword
        val url = "${baseUrl()}/auth/login"
        val body = json.encodeToString(IchigoLoginRequest(e, p))
        val authHeaders = browserAuthHeaders()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        // blocking execute() must run on IO: the auth screen calls from the main thread
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val txt = resp.body.string().take(4096)
                    when (resp.code) {
                        200 -> {
                            try {
                                val parsed = json.decodeFromString<IchigoAuthResponse>(txt)
                                storeTokens(parsed.tokens?.accessToken, parsed.tokens?.refreshToken)
                                if (accessToken().isNotBlank()) store.setEmail(e)
                            } catch (_: Exception) {
                            }
                            if (accessToken().isBlank()) {
                                val fallback = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                                if (!fallback.isNullOrBlank()) {
                                    storeTokens(fallback, null)
                                    store.setEmail(e)
                                }
                            }
                            // Only report success when a session token was actually stored.
                            if (accessToken().isBlank()) IchigoLoginResult.Unknown else IchigoLoginResult.Success
                        }
                        400 -> {
                            val lower = txt.lowercase()
                            val detail = Regex(""""kind"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)?.lowercase()
                            when {
                                detail == "emptyEmail" -> IchigoLoginResult.InvalidEmail
                                detail == "userNotFound" -> IchigoLoginResult.UnknownEmail
                                lower.contains("invalidcredentials") || lower.contains("bad username") || lower.contains("invalid email") -> IchigoLoginResult.BadPassword
                                else -> {
                                    logcat { "Ichigo login 400 unhandled: $txt" }
                                    IchigoLoginResult.Unknown
                                }
                            }
                        }
                        401, 403 -> IchigoLoginResult.BadPassword
                        429 -> IchigoLoginResult.RateLimited
                        else -> {
                            logcat { "Ichigo login HTTP ${resp.code}: $txt" }
                            IchigoLoginResult.Unknown
                        }
                    }
                }
            } catch (e: Exception) {
                logcat { "Ichigo login failed: ${e.message}" }
                IchigoLoginResult.Unknown
            }
        }
    }

    suspend fun signup(email: String, password: String): IchigoSignupResult {
        val e = email.trim().take(254)
        val p = password.take(512)
        if (e.isBlank() || !e.contains("@") || e.length < 5) return IchigoSignupResult.InvalidEmail
        if (p.length < 6) return IchigoSignupResult.Unknown
        val url = "${baseUrl()}/signup"
        val body = json.encodeToString(IchigoLoginRequest(e, p))
        val authHeaders = browserAuthHeaders()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val txt = resp.body.string().take(8192)
                    when (resp.code) {
                        201, 200 -> {
                            try {
                                val parsed = json.decodeFromString<IchigoAuthResponse>(txt)
                                storeTokens(parsed.tokens?.accessToken, parsed.tokens?.refreshToken)
                                if (accessToken().isNotBlank()) store.setEmail(e)
                            } catch (_: Exception) {}
                            val fallback = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                            if (!fallback.isNullOrBlank() && accessToken().isBlank()) {
                                storeTokens(fallback, null)
                                store.setEmail(e)
                            }
                            if (accessToken().isBlank()) IchigoSignupResult.Unknown else IchigoSignupResult.Success
                        }
                        400 -> {
                            val lower = txt.lowercase()
                            val detail = Regex(""""kind"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)?.lowercase()
                            when {
                                detail == "emptyEmail" -> IchigoSignupResult.InvalidEmail
                                lower.contains("invalid") || lower.contains("email") -> IchigoSignupResult.InvalidEmail
                                else -> {
                                    logcat { "Ichigo signup 400: $txt" }
                                    IchigoSignupResult.Unknown
                                }
                            }
                        }
                        401, 403 -> {
                            val lower = txt.lowercase()
                            if (lower.contains("email taken") || lower.contains("already") || lower.contains("exists") || lower.contains("taken")) {
                                IchigoSignupResult.EmailTaken
                            } else {
                                IchigoSignupResult.EmailTaken
                            }
                        }
                        422 -> {
                            logcat { "Ichigo signup 422: $txt" }
                            IchigoSignupResult.InvalidEmail
                        }
                        429 -> IchigoSignupResult.RateLimited
                        else -> {
                            logcat { "Ichigo signup HTTP ${resp.code}: $txt" }
                            IchigoSignupResult.Unknown
                        }
                    }
                }
            } catch (e: Exception) {
                logcat { "Ichigo signup failed: ${e.message}" }
                IchigoSignupResult.Unknown
            }
        }
    }

    suspend fun logout(): Boolean {
        val token = accessToken()
        val url = "${baseUrl()}/auth/logout"
        val authHeaders = browserAuthHeaders()
        val builder = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        val ok = try {
            withContext(Dispatchers.IO) {
                client.newCall(builder.build()).execute().use { resp ->
                    resp.code == 204 || resp.code == 200 || resp.code == 401 || resp.code == 403
                }
            }
        } catch (_: Exception) {
            false
        }
        clearAuth()
        // Do not clear email — keep for UI convenience, matching the extension's email retention.
        return ok
    }

    fun clearAuth() {
        // Clear both session tokens (access + refresh).
        store.setAccessToken("")
        store.setRefreshToken("")
    }

    fun isLoggedIn(): Boolean = accessToken().isNotBlank()

    // The retry helper is handed in untyped, so the decoded user comes back as Any?.
    @Suppress("UNCHECKED_CAST")
    suspend fun getCurrentUser(): IchigoUser? {
        val url = "${baseUrl()}/metrics?clientUuid=${fingerprint.clientUuid()}&fingerprint=${fingerprint.fingerprint()}"
        val headers = ichigoHeaders()
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return try {
            @Suppress("UNCHECKED_CAST")
            executeWithAuthRetry(req) { r ->
                val txt = r.body.string().take(8192)
                if (r.code == 200) {
                    try {
                        json.decodeFromString<IchigoUser>(txt)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            } as IchigoUser?
        } catch (_: Exception) {
            null
        }
    }
}
