package eu.kanade.tachiyomi.data.connections.discord

import exh.log.xLogE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class RPCExternalAsset(
    applicationId: String,
    private val token: String,
    private val client: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "RPCExternalAsset"

        // KMK --> A source URL converts to a stable mp: path, so one call per URL is all the
        // endpoint ever needs from this process. The service asks on every presence update -
        // chapter turns, screen switches - which was both pointless traffic and the rate-limit
        // exposure behind "presence has no icons": a 429 (or any transient failure) silently
        // dropped the image for that update, and the next update asked again. LRU, access
        // order, process-wide: instances of this class are created per update.
        private const val CACHE_MAX_ENTRIES = 16
        private val uriCache = object : LinkedHashMap<String, String>(CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > CACHE_MAX_ENTRIES
        }
        private val uriCacheLock = Any()

        private fun cached(imageUrl: String): String? = synchronized(uriCacheLock) { uriCache[imageUrl] }

        private fun remember(imageUrl: String, mpUri: String) = synchronized(uriCacheLock) {
            uriCache[imageUrl] = mpUri
        }
        // KMK <--
    }

    @Serializable
    data class ExternalAsset(
        val url: String? = null,
        @SerialName("external_asset_path")
        val externalAssetPath: String? = null,
    )

    private val api = "https://discord.com/api/v9/applications/$applicationId/external-assets"
    suspend fun getDiscordUri(imageUrl: String): String? {
        if (imageUrl.startsWith("mp:")) return imageUrl
        cached(imageUrl)?.let { return it }
        val request = Request.Builder().url(api).header("Authorization", token)
            .post("{\"urls\":[\"$imageUrl\"]}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val res = client.newCall(request).await()
            if (res.code == 429) {
                // Rate limit hit
                Timber.tag(TAG).e("Discord API rate limit reached: ${res.body.string()}")
                return null
            }
            if (!res.isSuccessful) {
                val detail = res.body.string()
                Timber.tag(TAG).e("Discord API error: HTTP ${res.code} - $detail")
                // KMK --> Timber only reaches logcat, and this is the one line a "presence
                // has no images" report needs (401 = stale/unauthorized token on this
                // install, 429 = rate limit). Mirror it into the in-app log file so the
                // failure is visible without adb.
                xLogE("Discord external asset failed: HTTP ${res.code} - $detail")
                return null
            }
            json.decodeFromString<List<ExternalAsset>>(res.body.string())
                .firstOrNull()?.externalAssetPath?.let { "mp:$it" }
                // KMK --> Only a real conversion is cached; the endpoint's "external/Not
                // Found" payload (unfetchable source URL) must not poison the entry.
                ?.takeIf { it.isNotBlank() && !it.contains("external/Not Found") }
                ?.also { remember(imageUrl, it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e("Exception while fetching Discord external asset: ${e.message}")
            null
        }
    }

    private suspend inline fun Call.await(): Response {
        return suspendCoroutine {
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    it.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    it.resume(response)
                }
            })
        }
    }
}
