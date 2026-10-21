package eu.kanade.tachiyomi.data.track.animeplanet

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.core.TrackerLoginMode
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.setting.track.AnimePlanetLoginActivity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class AnimePlanet(id: Long) : BaseTracker(id, "AnimePlanet"), DeletableTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val PLAN_TO_READ = 4L
        const val DROPPED = 5L
        const val REREADING = 6L

        /**
         * Site-domain status for "Won't Read" (the widget's value 6). Note this deliberately
         * clashes with the internal REREADING value: entry states parsed off the site live in
         * the site's value space and [fromApiStatus] translates them. The former tracker mapped
         * REREADING onto this value, quietly marking re-reads as never going to happen - the
         * site has no re-reading status, so [getRereadingStatus] returns READING instead.
         */
        const val WONT_READ = 6L

        // KMK -->
        private const val ANON_USERNAME = "animeplanet-user"
        // KMK <--

        private val SCORE_LIST = (0..10)
            .map { if (it == 0) "-" else it.toString() }
            .toImmutableList()

        /** Site list statuses by value: 1 Read, 2 Reading, 3 Dropped, 4 Want to Read, 5 Stalled. */
        fun toApiStatus(status: Long): Int = when (status) {
            READING -> 2
            COMPLETED -> 1
            ON_HOLD -> 5
            PLAN_TO_READ -> 4
            DROPPED -> 3
            REREADING -> 2
            else -> 4
        }

        /** Inverse of [toApiStatus]; only call for site statuses 1..5 (0 and 6 are not tracked). */
        fun fromApiStatus(apiStatus: Long): Long = when (apiStatus) {
            1L -> COMPLETED
            2L -> READING
            3L -> DROPPED
            4L -> PLAN_TO_READ
            5L -> ON_HOLD
            else -> READING
        }

        /** Anime-Planet rates on a half-star 0.5..5.0 scale; the tracker scores 0..10. */
        fun siteToTrackerRating(siteRating: Double): Double = siteRating * 2.0
    }

    private val interceptor by lazy { AnimePlanetInterceptor(this) }
    private val webClient by lazy { AnimePlanetWebClient(globalAppGraph.context) }
    private val api by lazy { AnimePlanetApi(id, interceptor, client, webClient) }

    override fun getLogo(): Int = R.drawable.brand_animeplanet

    override fun getStatusList(): List<Long> {
        // Anime-Planet has no re-reading status on its site (REREADING stays a reserved
        // internal value for legacy manga_sync rows; the wire maps it onto READING).
        return listOf(READING, COMPLETED, ON_HOLD, PLAN_TO_READ, DROPPED)
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        PLAN_TO_READ -> MR.strings.plan_to_read
        DROPPED -> MR.strings.dropped
        REREADING -> MR.strings.repeating
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = READING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toInt().toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED && didReadChapter) {
            track.status = READING
        }
        val slug = extractSlugFromUrl(track.tracking_url)
        if (slug != null) {
            if (track.remote_id == 0L) {
                throw Exception("Anime-Planet entry for ${track.title} has no remote id; bind it first")
            }
            api.updateMangaListEntry(
                track.remote_id,
                slug,
                track.status,
                track.last_chapter_read.toInt(),
                track.score,
            )
        }
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        val slug = extractSlugFromUrl(track.remoteUrl)
        if (slug != null) {
            api.deleteMangaFromList(track.remoteId, slug)
        }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val slug = extractSlugFromUrl(track.tracking_url) ?: return track

        val page = api.getMangaPage(slug)
        if (page.remoteId == 0L) {
            // Challenge or login page: the fetch did not resolve the entry, so keep the
            // legacy heuristic instead of zeroing an existing id.
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            return track
        }

        track.remote_id = page.remoteId
        track.total_chapters = page.totalChapters

        // First binding: prefer the state the user already has on Anime-Planet, fall back to
        // what this library entry knows.
        if (page.trackedRemotely) {
            track.status = fromApiStatus(page.entryStatus)
            track.last_chapter_read = page.entryChapters.toDouble()
            if (page.entryRating > 0) {
                track.score = siteToTrackerRating(page.entryRating)
            }
        } else {
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
        }
        return track
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query)
    }

    override suspend fun refresh(track: Track): Track {
        val slug = extractSlugFromUrl(track.tracking_url) ?: return track

        val page = api.getMangaPage(slug)
        if (page.remoteId == 0L) {
            // The page did not render the list widget at all: expired login or a challenge
            // slip-through. Fail loudly, like the other trackers do, instead of zeroing ids.
            throw Exception("Could not read Anime-Planet page for $slug (login or Cloudflare mismatch)")
        }

        track.remote_id = page.remoteId
        track.title = page.title
        track.total_chapters = page.totalChapters

        // Two-way sync: when the entry is on the user's Anime-Planet list, its remote state is
        // the truth for status, progress and score. "Won't Read" and missing entries count as
        // not tracked, leaving the local row untouched.
        if (page.trackedRemotely) {
            track.status = fromApiStatus(page.entryStatus)
            track.last_chapter_read = page.entryChapters.toDouble()
            if (page.entryRating > 0) {
                track.score = siteToTrackerRating(page.entryRating)
            }
        }
        return track
    }

    override suspend fun login(username: String, password: String) = loginWithCookie(password, username)

    // KMK -->
    suspend fun loginWithCookie(cookieHeader: String, username: String = "") {
        saveCredentials(username.ifBlank { ANON_USERNAME }, cookieHeader)
        interceptor.newAuth(cookieHeader)
    }

    /**
     * Persist the current cookie header string to preferences so the
     * [AnimePlanetInterceptor] can restore its [CookieJar] on restart.
     */
    fun saveCookieHeader(cookieHeader: String) {
        trackPreferences.trackPassword(this).set(cookieHeader)
    }
    // KMK <--

    fun restoreSession(): String? {
        return trackPreferences.trackPassword(this).get().ifBlank { null }
    }

    // KMK -->
    /**
     * Returns the full cookie header string for API requests.
     * Handles backward compatibility: old installs stored just the session value,
     * new installs store the full "name=value; name2=value2" cookie header.
     */
    fun restoreCookieHeader(): String? {
        val stored = trackPreferences.trackPassword(this).get().ifBlank { null } ?: return null
        // If it already contains "; " or "=", it's a full cookie header (new format)
        return if (stored.contains("=") && stored.contains("; ")) {
            stored
        } else if (stored.contains("=")) {
            // Single cookie like "session=abc123" — wrap as-is
            stored
        } else {
            // Legacy: bare value without key — assume it's the session cookie
            "session=$stored"
        }
    }
    // KMK <--

    private fun extractSlugFromUrl(url: String): String? {
        return Regex("""/manga/([^/?]+)""").find(url)?.groupValues?.get(1)
    }

    // KMK -->
    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ
    // KMK <--
    override fun getLoginMode(): TrackerLoginMode = TrackerLoginMode.WEBVIEW_COOKIE

    override fun createCookieLoginIntent(context: android.content.Context): android.content.Intent? =
        AnimePlanetLoginActivity.newIntent(context)
}
