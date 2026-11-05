package eu.kanade.tachiyomi.source.online.all.komga

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * One OkHttp setup for the Komga API, shared by the source, covers and pages. The auth
 * contract is the one the server documents: `X-API-Key` wins when set, otherwise HTTP
 * Basic credentials supplied by the [Authenticator] (Komga answers a request without
 * them with a 401 and asks for them).
 *
 * `Dns.SYSTEM` on purpose: Komga servers are commonly reached by IP or a local resolver,
 * and the app's default DoH would bypass that with a lookup the network cannot answer.
 *
 * The interceptor reads the key **per request** instead of baking it into a client:
 * rotating the key in settings takes effect on the next fetch without rebuilding anything.
 */
object KomgaClient {

    fun defaultHeaders(prefs: SharedPreferences, userAgent: String): Headers =
        Headers.Builder()
            .add("User-Agent", userAgent)
            .apply {
                // Cover/page fetchers read source.headers, so the key rides along there too.
                val apiKey = prefs.getString(KomgaSettingKeys.API_KEY, "")
                if (!apiKey.isNullOrBlank()) set("X-API-Key", apiKey)
            }
            .build()

    fun client(prefs: SharedPreferences, network: NetworkHelper, userAgent: String): OkHttpClient =
        network.client.newBuilder()
            .dns(Dns.SYSTEM)
            .addInterceptor(apiKeyInterceptor(prefs))
            .authenticator(basicAuth(prefs))
            .build()

    private fun apiKeyInterceptor(prefs: SharedPreferences): Interceptor = Interceptor { chain ->
        val apiKey = prefs.getString(KomgaSettingKeys.API_KEY, "")!!
        if (apiKey.isBlank()) {
            chain.proceed(chain.request())
        } else {
            chain.proceed(
                chain.request().newBuilder().header("X-API-Key", apiKey).build(),
            )
        }
    }

    private fun basicAuth(prefs: SharedPreferences): Authenticator = Authenticator { _, response ->
        val apiKey = prefs.getString(KomgaSettingKeys.API_KEY, "")!!
        val username = prefs.getString(KomgaSettingKeys.USERNAME, "")!!
        val password = prefs.getString(KomgaSettingKeys.PASSWORD, "")!!
        val configured = prefs.serverBase()

        // API-key clients never answer a challenge, and we never authenticate twice.
        if (apiKey.isNotBlank() || response.request.header("Authorization") != null) {
            return@Authenticator null
        }
        if (username.isBlank() || password.isBlank()) {
            return@Authenticator null
        }
        // The challenge should only ever come from the configured server; refuse anything else.
        if (configured.isBlank() || !response.request.url.toString().startsWith(configured)) {
            return@Authenticator null
        }
        response.request.newBuilder()
            .header("Authorization", Credentials.basic(username, password))
            .build()
    }
}
