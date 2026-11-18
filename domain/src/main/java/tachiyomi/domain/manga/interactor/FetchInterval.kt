package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Works out the entry's fetch interval and the next day to check it for new chapters, from the
 * release schedule its chapter history shows (ported from Mihon, issue #81).
 *
 * A user-set interval (negative, and the disabled marker) is honoured as a fixed schedule counted
 * from the last release; anything else is estimated from the chapters' release days, whose calendar
 * pattern a plain day count cannot follow. The result is a [MangaUpdate] that also carries the
 * interval, so the caller persists the two together.
 */
@Inject
class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        foundRelease: Boolean = false,
        excludedScanlators: Set<String> = emptySet(),
    ): MangaUpdate = toMangaUpdate(
        manga = manga,
        dateTime = dateTime,
        window = window,
        chapters = getChaptersByMangaId.await(manga.id),
        foundRelease = foundRelease,
        excludedScanlators = excludedScanlators,
    )

    /**
     * [chapters]-taking variant for callers that already hold the chapter list in hand.
     *
     * The restore is the one caller: a backup that carries no update schedule of its own
     * (every entry of a Mihon-format backup, or one written before the schedule existed)
     * must have the schedule computed per entry, and reading it back from the table
     * re-fetched every chapter row the restore had just written - a third full chapter
     * read per entry. Unlike the single-argument path this applies no filters itself:
     * the restore is making these rows authoritative, so the cadence comes from all of
     * them unless an exclusion set is passed.
     *
     * The estimate is fed unfiltered chapters: the cadence must follow what the entry
     * actually ships, not what the reader's display filter is currently set to show.
     * User-excluded scanlators are dropped via [excludedScanlators] (Mihon's
     * applyScanlatorFilter); the set lives outside this module, so callers pass it in.
     */
    suspend fun toMangaUpdate(
        manga: Manga,
        dateTime: ZonedDateTime,
        window: Pair<Long, Long>,
        chapters: List<Chapter>,
        foundRelease: Boolean = false,
        excludedScanlators: Set<String> = emptySet(),
    ): MangaUpdate {
        val zone = dateTime.zone
        val today = dateTime.toLocalDate()
        val allChapters = if (excludedScanlators.isEmpty()) chapters else chapters.filterNot { it.scanlator in excludedScanlators }
        val currentWindow = if (window.first == 0L && window.second == 0L) {
            getWindow(ZonedDateTime.now())
        } else {
            window
        }

        if (manga.fetchInterval == MANUAL_DISABLE) {
            // A disabled entry is checked again effectively never. It must not go through the
            // schedule machinery, whose checks are capped at MAX_INTERVAL days.
            val farFuture = today.plusDays(MANUAL_DISABLE.toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
            return MangaUpdate(id = manga.id, nextUpdate = farFuture, fetchInterval = MANUAL_DISABLE)
        }

        val schedule = if (manga.fetchInterval < 0) {
            // The check that found a release is the entry's last update
            val lastRelease = if (foundRelease) {
                today
            } else {
                manga.lastUpdate.takeIf { it > 0L }
                    ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            }
            ReleaseSchedule.fixed(-manga.fetchInterval, lastRelease ?: today)
        } else {
            ReleaseSchedule.estimate(allChapters, zone, today)
        }

        val expected = manga.nextUpdate
        val expectedDay = expected.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        // A release on or after the expected day means the next one is counted from it instead
        val isAwaitingRelease = expectedDay != null &&
            expected in currentWindow.first..currentWindow.second + 1L &&
            (schedule.lastRelease == null || schedule.lastRelease < expectedDay)
        val nextUpdate = when {
            foundRelease -> schedule.nextCheckAfterRelease(today)
            isAwaitingRelease -> expectedDay
            else -> schedule.nextCheck(today)
        }.atStartOfDay(zone).toInstant().toEpochMilli()

        return MangaUpdate(
            id = manga.id,
            nextUpdate = nextUpdate,
            fetchInterval = if (manga.fetchInterval < 0) manga.fetchInterval else schedule.interval,
        )
    }

    fun getWindow(dateTime: ZonedDateTime): Pair<Long, Long> {
        val today = dateTime.toLocalDate().atStartOfDay(dateTime.zone)
        val lowerBound = today.minusDays(GRACE_PERIOD)
        val upperBound = today.plusDays(GRACE_PERIOD)
        return Pair(lowerBound.toEpochSecond() * 1000, upperBound.toEpochSecond() * 1000 - 1)
    }

    companion object {
        const val MAX_INTERVAL = 28

        private const val GRACE_PERIOD = 1L

        // KMK -->
        const val MANUAL_DISABLE = 99999 // 274 years in future
        // KMK <--
    }
}
