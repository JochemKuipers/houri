package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.data.DatabaseHandler

@Inject
class SetExcludedScanlators(
    private val handler: DatabaseHandler,
) {

    suspend fun await(mangaId: Long, excludedScanlators: Set<String>) {
        handler.await(inTransaction = true) {
            val currentExcluded = handler.awaitList {
                excluded_scanlatorQueries.getExcludedScanlatorsByMangaId(mangaId)
            }.toSet()
            val toAdd = excludedScanlators.minus(currentExcluded)
            for (scanlator in toAdd) {
                excluded_scanlatorQueries.insert(mangaId, scanlator)
            }
            val toRemove = currentExcluded.minus(excludedScanlators)
            // An empty delete still tells every chapter query to run again
            if (toRemove.isNotEmpty()) {
                excluded_scanlatorQueries.remove(mangaId, toRemove)
            }
        }
    }
}
