package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.CoroutineScope
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.scanlatorBlacklistKey
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * Scanlator visibility controls for the manga details screen, extracted from [MangaViewModel]:
 * excluded-scanlator set, scanlator display priority, and the chapter blacklist.
 *
 * The blacklist keying follows the sibling rule (issue #39): a scanlator that published one
 * chapter number twice produces the same broad key for both, so the narrow (upload-qualified)
 * key is only used when a sibling with the same number and scanlator exists; a lone upload
 * keeps the broad key, which survives the url changing on a later fetch.
 */
internal class MangaScanlatorController(
    private val setExcludedScanlators: SetExcludedScanlators,
    private val updateManga: UpdateManga,
    private val getManga: GetManga,
    private val provideMangaId: () -> Long,
    private val provideAllChapters: () -> List<Chapter>?,
    private val provideBlacklistedChapters: () -> List<String>,
) {
    /**
     * The blacklist as the database holds it, falling back to the screen's snapshot.
     *
     * Every edit is read-modify-write, and the snapshot it used to read is one emission behind
     * whenever two edits land close together: blacklist a chapter, blacklist the next one before
     * the first write's emission arrives, and the second write is built on a list that does not
     * contain the first key - which silently drops it. Reading the row makes the previous edit
     * part of the next one regardless of how fast the taps come.
     */
    private suspend fun currentBlacklist(): List<String> =
        getManga.await(provideMangaId())?.blacklistedChapters ?: provideBlacklistedChapters()

    /**
     * Runs [block] off the main thread on a scope that outlives this screen.
     *
     * The blacklist is written from a details screen the user can leave with the very next
     * gesture. These used to run on the ViewModel's scope, which the screen's pop cancels: a write
     * still queued at that moment never ran, and the chapter was back on the next open (issue
     * #68, "blacklisted chapters are not saved"). The app-wide `launchIO` is not tied to the
     * screen and what it runs is one row's update, so there is nothing to leak.
     */
    private fun writeNow(block: suspend CoroutineScope.() -> Unit) {
        launchIO(block)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        writeNow {
            setExcludedScanlators.await(provideMangaId(), excludedScanlators)
        }
    }

    // KMK -->
    fun setScanlatorPriority(priority: List<String>) {
        writeNow {
            updateManga.await(MangaUpdate(id = provideMangaId(), scanlatorPriority = priority))
        }
    }

    fun setBlacklistedChapters(blacklistedChapters: List<String>) {
        writeNow {
            updateManga.await(MangaUpdate(id = provideMangaId(), blacklistedChapters = blacklistedChapters))
        }
    }

    fun blacklistChapters(chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        writeNow {
            // KMK --> A scanlator that published one number twice produces the same broad key for
            // both, and blacklisting one would take the other with it (issue #39). Narrow to the
            // upload only in that case; a lone upload keeps the broad key, which survives the url
            // changing on a later fetch.
            val siblings = provideAllChapters().orEmpty()
            val newEntries = chapters.map { chapter ->
                val hasSiblings = siblings.any { other ->
                    other.id != chapter.id &&
                        other.chapterNumber == chapter.chapterNumber &&
                        other.scanlator?.trim().orEmpty() == chapter.scanlator?.trim().orEmpty()
                }
                if (hasSiblings && chapter.url.isNotBlank()) {
                    scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator, chapter.url)
                } else {
                    scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator)
                }
            }
            // KMK <--
            val current = currentBlacklist()
            updateManga.await(
                MangaUpdate(
                    id = provideMangaId(),
                    blacklistedChapters = (current + newEntries).distinct(),
                ),
            )
        }
    }

    fun removeBlacklistedChapters(keys: List<String>) {
        if (keys.isEmpty()) return
        writeNow {
            val current = currentBlacklist()
            updateManga.await(
                MangaUpdate(
                    id = provideMangaId(),
                    blacklistedChapters = current - keys.toSet(),
                ),
            )
        }
    }
    // KMK <--
}
