package eu.kanade.tachiyomi.ui.manga

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga

// KMK -->
@Immutable
sealed interface RelatedManga {
    data object Loading : RelatedManga

    data class Success(
        val keyword: String,
        val mangaList: List<Manga>,
    ) : RelatedManga {
        val isEmpty: Boolean
            get() = mangaList.isEmpty()

        companion object {
            suspend fun fromPair(
                pair: Pair<String, List<SManga>>,
                toManga: suspend (mangaList: List<SManga>) -> List<Manga>,
            ) = Success(pair.first, toManga(pair.second))
        }
    }

    fun isVisible(): Boolean {
        return this is Loading || (this is Success && !this.isEmpty)
    }

    companion object {
        internal fun List<RelatedManga>.sorted(manga: Manga): List<RelatedManga> {
            val success = filterIsInstance<Success>()
            val loading = filterIsInstance<Loading>()
            val title = manga.title.lowercase()
            val ogTitle = manga.ogTitle.lowercase()
            return success.filter { it.keyword.isEmpty() } +
                success.filter { it.keyword.lowercase() == title } +
                success.filter { it.keyword.lowercase() == ogTitle && ogTitle != title } +
                success.filter { it.keyword.isNotEmpty() && it.keyword.lowercase() !in listOf(title, ogTitle) }
                    .sortedByDescending { it.keyword.length }
                    .sortedBy { it.mangaList.size } +
                loading
        }

        internal fun List<RelatedManga>.removeDuplicates(manga: Manga): List<RelatedManga> {
            val mangaIds = HashSet<Long>().apply { add(manga.id) }

            return map { relatedManga ->
                if (relatedManga is Success) {
                    Success(
                        relatedManga.keyword,
                        relatedManga.mangaList
                            .filter { mangaIds.add(it.id) },
                    )
                } else {
                    relatedManga
                }
            }
        }

        internal fun List<RelatedManga>.isLoading(isRelatedMangaFetched: Boolean?): List<RelatedManga> {
            return if (isRelatedMangaFetched == false) this + listOf(Loading) else this
        }
    }
}
// KMK <--
