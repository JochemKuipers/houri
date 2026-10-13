package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

/** A library entry together with the entries it duplicates. */
data class DuplicateEntry(
    val manga: Manga,
    val duplicates: List<MangaWithChapterCount>,
)

/**
 * Finds every library entry that has at least one duplicate, so they can be reviewed in one place
 * rather than only being surfaced at the moment something is added to the library.
 *
 * Matching is the same as when adding: a shared title substring, or the same remote entry on the
 * same tracker. That means the scan issues one query per library entry, which is why this runs only
 * when the screen is opened rather than on any library update.
 */
@Inject
class GetLibraryDuplicates(
    private val getLibraryManga: GetLibraryManga,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
) {

    suspend fun await(): List<DuplicateEntry> {
        val results = mutableListOf<DuplicateEntry>()
        for (item in getLibraryManga.await()) {
            val duplicates = getDuplicateLibraryManga(item.manga)
            if (duplicates.isNotEmpty()) {
                results.add(DuplicateEntry(manga = item.manga, duplicates = duplicates))
            }
        }
        return results
    }
}
