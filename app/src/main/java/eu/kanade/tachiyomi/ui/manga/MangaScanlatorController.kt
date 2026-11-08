package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.CoroutineScope
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.scanlatorBlacklistKey
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
    private val scope: CoroutineScope,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val updateManga: UpdateManga,
    private val provideMangaId: () -> Long,
    private val provideAllChapters: () -> List<Chapter>?,
    private val provideBlacklistedChapters: () -> List<String>,
) {
    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        scope.launchIO {
            setExcludedScanlators.await(provideMangaId(), excludedScanlators)
        }
    }

    // KMK -->
    fun setScanlatorPriority(priority: List<String>) {
        scope.launchIO {
            updateManga.await(MangaUpdate(id = provideMangaId(), scanlatorPriority = priority))
        }
    }

    fun setBlacklistedChapters(blacklistedChapters: List<String>) {
        scope.launchIO {
            updateManga.await(MangaUpdate(id = provideMangaId(), blacklistedChapters = blacklistedChapters))
        }
    }

    fun blacklistChapters(chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        scope.launchIO {
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
            val current = provideBlacklistedChapters()
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
        scope.launchIO {
            val current = provideBlacklistedChapters()
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
