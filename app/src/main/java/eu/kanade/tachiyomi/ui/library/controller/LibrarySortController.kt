package eu.kanade.tachiyomi.ui.library.controller

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.util.removeArticles
import java.text.CollationKey
import tachiyomi.core.common.util.lang.toCollationKey
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track
import kotlin.random.Random

/**
 * Sorts the grouped library, extracted from [eu.kanade.tachiyomi.ui.library.LibraryViewModel].
 *
 * Every group's list of manga ids is sorted by the group's own [LibrarySort] (or the
 * [groupSort] override while the library is grouped by a non-default scheme), with the
 * alphabetical comparator as tie-break. Random sorts shuffle with the stored seed so the
 * order survives recomposition.
 */
class LibrarySortController(
    private val libraryPreferences: LibraryPreferences,
    private val trackerManager: TrackerManager,
) {
    fun sort(
        groups: Map<Category, List</* LibraryItem */ Long>>,
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
        // SY -->
        groupSort: LibrarySort? = null,
        // SY <--
    ): Map<Category, List</* LibraryItem */ Long>> {
        // SY -->
        val listOfTags by lazy {
            libraryPreferences.sortTagsForLibrary().get()
                .asSequence()
                .mapNotNull {
                    val list = it.split("|")
                    (list.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to
                        (list.getOrNull(1) ?: return@mapNotNull null)
                }
                .sortedBy { it.first }
                .map { it.second }
                .toList()
        }
        // SY <--

        // KMK -->
        val ignoreArticles = libraryPreferences.ignoreArticlesInSort().get()

        // The alphabetical comparator used to do the whole prep on every comparison: lowercase
        // plus article removal on both sides, then a full collator pass on top - and every sort
        // here hangs it on as the tie-break, with the whole method re-running per library
        // emission. On a large library that is an O(n log n) scan over the entire title list per
        // sort. Now each entry's title is prepped and collated exactly once per pass and the
        // comparator compares the cached CollationKey (byte array compare, same ordering as
        // collator.compare), which turns the sort into O(n) collator passes.
        val collationKeys = HashMap</* Manga */ Long, CollationKey>(favoritesById.size)
        fun collationKeyOf(item: LibraryItem): CollationKey = collationKeys.getOrPut(item.libraryManga.manga.id) {
            var title = item.libraryManga.manga.title.lowercase()
            if (ignoreArticles) title = title.removeArticles()
            title.toCollationKey()
        }
        // KMK <--

        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            collationKeyOf(manga1).compareTo(collationKeyOf(manga2))
        }

        val defaultTrackerScoreSortValue = -1.0
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it) }
                            .average()
                }
            }
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            // SY -->
            val sort = groupSort ?: this
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }
                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }
                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }
                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }
                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
                // SY -->
                LibrarySort.Type.TagList -> {
                    val manga1IndexOfTag = listOfTags.indexOfFirst {
                        manga1.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    val manga2IndexOfTag = listOfTags.indexOfFirst {
                        manga2.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    manga1IndexOfTag.compareTo(manga2IndexOfTag)
                }
                // SY <--
            }
        }

        return groups.mapValues { (key, value) ->
            // SY -->
            val sort = groupSort ?: key.sort
            if (sort.type == LibrarySort.Type.Random) {
                // SY <--
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed().get()))
            }

            val manga = value.mapNotNull { favoritesById[it] }

            // SY -->
            val comparator = sort.comparator()
                // SY <--
                .let { if (/* SY --> */ sort.isAscending /* SY <-- */) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            manga.sortedWith(comparator).map { it.id }
        }
    }
}
