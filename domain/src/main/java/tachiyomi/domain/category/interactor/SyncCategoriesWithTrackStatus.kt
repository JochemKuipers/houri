package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Keeps status categories in step with a manga's tracker status.
 *
 * Only categories explicitly bound to a status take part. An unbound category is left exactly as it
 * was, so a folder the user named "Reading" for their own reasons is never touched. A manga is
 * moved into the category bound to its current status and taken out of every other bound category;
 * binding the same status to two categories puts it in both.
 *
 * A null [trackStatus] means the manga is no longer tracked to any real status, which clears it out
 * of every bound category rather than guessing which one it should sit in.
 */
@Inject
class SyncCategoriesWithTrackStatus(
    private val categoryRepository: CategoryRepository,
    private val setMangaCategories: SetMangaCategories,
) {

    suspend fun await(mangaId: Long, trackStatus: Int?) {
        if (mangaId <= 0L) return
        val bound = categoryRepository.getAll().filter { it.trackStatus != null }
        if (bound.isEmpty()) return

        val current = categoryRepository.getCategoriesByMangaId(mangaId).mapTo(mutableSetOf()) { it.id }
        val previous = current.toSet()
        val updated = updateMembership(current, bound, trackStatus) ?: return
        if (updated.toSet() == previous) return

        logcat(LogPriority.DEBUG) {
            "SyncCategoriesWithTrackStatus: manga $mangaId -> status $trackStatus, categories $updated"
        }
        setMangaCategories.await(mangaId, updated)
    }

    companion object {

        /**
         * Pure so the rules can be tested without a database. Returns null when no category is bound
         * at all, meaning there is nothing for this manga to be moved between.
         */
        fun updateMembership(
            current: MutableSet<Long>,
            boundCategories: List<Category>,
            trackStatus: Int?,
        ): List<Long>? {
            if (boundCategories.isEmpty()) return null
            for (category in boundCategories) {
                val bound = category.trackStatus ?: continue
                if (bound == trackStatus) {
                    current.add(category.id)
                } else {
                    current.remove(category.id)
                }
            }
            return current.toList()
        }
    }
}
