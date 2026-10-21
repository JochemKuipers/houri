package eu.kanade.tachiyomi.data.track.animeplanet

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tachiyomi.core.common.util.lang.withIOContext
import java.net.URLEncoder
import java.util.Locale

/**
 * Anime-Planet tracker API.
 *
 * The request recipes below were learned and verified from the live site in 2026-10 with the
 * api-anything toolkit (spec: ~/.api-anything/sites/anime-planet.json, ops `search`,
 * `getManga`, `entry-state`), by reading the site's own list-widget code
 * (dist/js/legacy_combined.js) and by observing it fire real requests:
 *
 * Reads (both server-rendered; one document answers everything about an entry):
 * - GET /manga/all?name={query}          search cards (title = img alt, cover = data-src)
 * - GET /manga/{slug}                    detail page: title (h1), summary (meta description),
 *                                        cover (meta og:image); id and the signed-in user's
 *                                        list state arrive inside the form.myListBar widget:
 *                                        data-id, select.changeStatus option[selected]@value,
 *                                        select.chapters option[selected]@value,
 *                                        select.chapters@data-eps (total), .rateme@name (rating)
 * Writes (jQuery getJSON with the `var TOKEN` CSRF token from the same page):
 * - GET /api/list/status/manga/{id}/{status}/{token}      1 Read, 2 Reading, 3 Dropped,
 *                                                        4 Want to Read, 5 Stalled,
 *                                                        6 Won't Read; 0 removes the entry
 * - GET /api/list/update/manga/{id}/{chapters}/0/{token}  chapters read (the 0 is volumes)
 * - GET /api/list/rate/manga/{id}/{rating}/{token}        rating 0.5..5.0 in half steps;
 *                                                        the path segment is "rate", NOT
 *                                                        "rating" - ratings are five-star
 *                                                        widget values, not a 0..10 scale
 * Every response body carries success/error plus status_id / rating echoes.
 */
class AnimePlanetApi(
    private val trackId: Long,
    private val interceptor: AnimePlanetInterceptor,
    private val client: OkHttpClient,
    private val webClient: AnimePlanetWebClient,
) {

    private val authClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    /** The WebView-fetched HTML is the complete server-rendered document; parse it as-is. */
    private fun fetchDocument(html: String): Document = Jsoup.parse(html)

    /**
     * Everything a single detail-page fetch can answer: the entry metadata and, when the user
     * tracks the manga on Anime-Planet, its list state. entryStatus is 0 when the manga is not
     * on the list at all, and 6 marks "Won't Read" - both count as not tracked here. Ratings
     * arrive on the site's half-star scale (0.5..5.0; 0 = unrated).
     */
    data class MangaPage(
        val remoteId: Long,
        val title: String,
        val summary: String,
        val coverUrl: String,
        val totalChapters: Long,
        val entryStatus: Long,
        val entryChapters: Int,
        val entryRating: Double,
    ) {
        val trackedRemotely: Boolean get() = entryStatus != AnimePlanet.WONT_READ && entryStatus != 0L
    }

    suspend fun search(query: String): List<TrackSearch> = withIOContext {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/manga/all?name=$encodedQuery"

        val document = fetchDocument(webClient.fetchHtml(url))

        document.select(".card").mapNotNull { card ->
            try {
                val link = card.selectFirst("""a[href*="/manga/"]""") ?: return@mapNotNull null
                val href = link.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val slug = href.substringAfterLast("/manga/").substringBefore("?")
                if (slug.isBlank()) return@mapNotNull null

                // The title link reads "Add to list <title>"; the cover img alt is the bare
                // title. Covers lazy-load: below the fold src is a placeholder and data-src
                // holds the CDN URL.
                val img = card.selectFirst("img")
                val coverUrl = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }
                val title = img?.attr("alt").orEmpty().ifBlank {
                    link.text().removePrefix("Add to list ")
                }

                TrackSearch.create(trackId).apply {
                    remote_id = 0
                    this.title = title
                    this.cover_url = coverUrl
                    tracking_url = "$BASE_URL/manga/$slug"
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Fetches a detail page once and parses the metadata plus the signed-in list state out of
     * it. Callers that only need a page token use [getToken].
     */
    suspend fun getMangaPage(slug: String): MangaPage = withIOContext {
        val document = fetchDocument(webClient.fetchHtml("$BASE_URL/manga/$slug"))
        parseMangaPage(document)
    }

    private fun parseMangaPage(document: Document): MangaPage {
        val listBar = document.selectFirst("form.myListBar")

        val mangaId = listBar?.attr("data-id")?.toLongOrNull()
            ?: findApVarsId(document)
            ?: 0L

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val summary = document.selectFirst("""meta[name="description"]""")?.attr("content").orEmpty()
        val coverUrl = document.selectFirst("""meta[property*="image"]""")?.attr("content").orEmpty()
            .ifBlank {
                document.selectFirst("""img[src*="cdn.anime-planet.com"]""")?.attr("src").orEmpty()
            }
        val totalChapters = listBar?.selectFirst("select.chapters")
            ?.attr("data-eps")
            ?.toLongOrNull()
            ?: 0L

        // The server marks the user's current selection with a selected option; when the entry
        // is not on the list the 0 placeholder carries it (or no select is rendered at all).
        val entryStatus = listBar
            ?.selectFirst("select.changeStatus option[selected]")
            ?.attr("value")
            ?.toLongOrNull()
            ?: 0L
        val entryChapters = if (entryStatus == 0L) {
            0
        } else {
            listBar?.selectFirst("select.chapters option[selected]")?.attr("value")?.toIntOrNull() ?: 0
        }
        val entryRating = listBar?.selectFirst(".rateme")?.attr("name")?.toDoubleOrNull() ?: 0.0

        return MangaPage(
            remoteId = mangaId,
            title = title,
            summary = summary,
            coverUrl = coverUrl,
            totalChapters = totalChapters,
            entryStatus = entryStatus,
            entryChapters = entryChapters,
            entryRating = entryRating,
        )
    }

    /** Legacy id fallback: AP_VARS is an inline script with the ENTRY_INFO id of this entry. */
    private fun findApVarsId(document: Document): Long? {
        return document.select("script")
            .firstOrNull { it.html().contains("AP_VARS") && it.html().contains("ENTRY_INFO") }
            ?.let { script ->
                Regex("""id:\s*["'](\d+)["']""").find(script.html())?.groupValues?.get(1)?.toLongOrNull()
            }
    }

    /**
     * Pushes status, progress and rating to a user's list.
     *
     * Returns true only when the *status* write is accepted, which is the one that decides
     * whether the entry exists at all; progress and rating are best-effort and do not fail the
     * update.
     *
     * The rating arrives on the tracker's 0..10 scale and is written on the site's half-star
     * scale, so it is halved: 10 becomes 5, 7 becomes 3.5 and 0 stays 0 (unrated).
     */
    suspend fun updateMangaListEntry(
        mangaId: Long,
        slug: String,
        status: Long,
        progress: Int,
        rating: Double,
    ): Boolean = withIOContext {
        val token = getToken(slug) ?: throw Exception("Could not extract token from $slug page")

        val statusResult = getListJson(
            url = "$BASE_URL/api/list/status/manga/$mangaId/${AnimePlanet.toApiStatus(status)}/$token",
        )
        val statusOk = statusResult?.second?.let { parseSuccess(it) } == true

        getListJson(url = "$BASE_URL/api/list/update/manga/$mangaId/$progress/0/$token")
            ?.second
            ?.let { parseSuccess(it) }

        updateRating(mangaId, rating, token)

        statusOk
    }

    /**
     * Writes the rating for a list entry on the site's 0.5..5.0 scale.
     *
     * Zero means "unrated" locally, which Anime-Planet expresses as rating 0 on this endpoint
     * rather than as an omitted call - the same shape as the status and progress writes.
     */
    private suspend fun updateRating(mangaId: Long, rating: Double, token: String) {
        val ratingValue = rating / SITE_RATING_TO_TRACKER_RATIO
        val formatted = formatRating(ratingValue)
        getListJson(url = "$BASE_URL/api/list/rate/manga/$mangaId/$formatted/$token")
            ?.second
            ?.let { parseSuccess(it) }
    }

    suspend fun deleteMangaFromList(mangaId: Long, slug: String): Boolean = withIOContext {
        val token = getToken(slug) ?: return@withIOContext false

        val result = getListJson(url = "$BASE_URL/api/list/status/manga/$mangaId/0/$token")
        result?.second?.let { parseSuccess(it) } == true
    }

    /**
     * Scrapes the per-session CSRF token that the site's own list-widget code appends to every
     * write URL; the corresponding `var TOKEN = '...'` script is rendered on every entry page.
     */
    private suspend fun getToken(slug: String): String? = withIOContext {
        try {
            val document = fetchDocument(webClient.fetchHtml("$BASE_URL/manga/$slug"))
            document.select("script")
                .firstOrNull { it.html().contains("var TOKEN") }
                ?.let { script ->
                    Regex("""var\s+TOKEN\s*=\s*['"]([^'"]+)['"]""").find(script.html())
                        ?.groupValues
                        ?.get(1)
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The site's own list-widget code calls these endpoints with jQuery getJSON, sending the
     * usual Ajax headers; nothing else is needed beyond the session cookies the interceptor
     * attaches.
     */
    private suspend fun getListJson(url: String): Pair<Int, String>? {
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        return authClient.newCall(request).await().use { response ->
            response.code to (response.body?.string() ?: "")
        }
    }

    /** An answer with no body counts as accepted; a JSON body must say success or fail. */
    private fun parseSuccess(body: String): Boolean {
        if (body.isBlank()) return true
        return try {
            val success = Json.parseToJsonElement(body).jsonObject["success"]
            success?.jsonPrimitive?.booleanOrNull ?: true
        } catch (e: Exception) {
            false
        }
    }

    /** Ratings on the wire are half steps ("3.5", "4"), never exponent or trailing zeros. */
    private fun formatRating(value: Double): String {
        val rounded = (value * 2).toInt() / 2.0
        val coerced = rounded.coerceIn(0.0, 5.0)
        return if (coerced == coerced.toInt().toDouble()) {
            coerced.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", coerced)
        }
    }

    companion object {
        private const val BASE_URL = "https://www.anime-planet.com"

        /** Tracker scores span 0..10; Anime-Planet rates on 0.5..5.0. */
        private const val SITE_RATING_TO_TRACKER_RATIO = 2.0
    }
}
