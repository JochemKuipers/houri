package exh.recs

import androidx.navigation3.runtime.NavKey
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import exh.recs.batch.RankedSearchResults
import exh.recs.batch.Results
import kotlinx.serialization.Serializable

/**
 * Nav3 routes for the recommends screens. `RankedSearchResults` (`Results<Map<SManga, Int>>`)
 * is only java.io.Serializable and SManga equality is identity-based, so routes carry these
 * kotlinx-serializable DTOs and the screens rebuild the real objects from them. The original
 * map is only ever iterated (paged display), so reconstructed instances are equivalent.
 *
 * Replaces the Voyager-era [RecommendsScreenArgs] / [BrowseRecommendsScreenArgs] payloads
 * that were passed directly through Screen constructors.
 */
// KMK --> Navigation 3
@Serializable
data class RecommendsRoute(
    val mangaId: Long,
    val sourceId: Long,
) : NavKey

@Serializable
data class MergedRecommendsRoute(
    val results: List<RankedSearchResultsDto>,
) : NavKey

@Serializable
data class BrowseRecommendsRoute(
    val mangaId: Long,
    val sourceId: Long,
    val recommendationSourceName: String,
    val isExternalSource: Boolean,
) : NavKey

@Serializable
data class BrowseMergedRecommendsRoute(
    val results: RankedSearchResultsDto,
    val isExternalSource: Boolean,
) : NavKey

@Serializable
data class RankedSearchResultsDto(
    val recSourceName: String,
    val recSourceCategoryResId: Int,
    val recAssociatedSourceId: Long?,
    val results: List<RankedMangaCountDto>,
) {
    fun toRankedSearchResults(): RankedSearchResults = Results(
        recSourceName = recSourceName,
        recSourceCategoryResId = recSourceCategoryResId,
        recAssociatedSourceId = recAssociatedSourceId,
        results = results.associate { it.toSManga() to it.count },
    )

    companion object {
        fun from(results: RankedSearchResults): RankedSearchResultsDto = RankedSearchResultsDto(
            recSourceName = results.recSourceName,
            recSourceCategoryResId = results.recSourceCategoryResId,
            recAssociatedSourceId = results.recAssociatedSourceId,
            results = results.results.entries.map { RankedMangaCountDto.from(it.key, it.value) },
        )
    }
}

@Serializable
data class RankedMangaCountDto(
    val url: String = "",
    val title: String = "",
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val thumbnailUrl: String? = null,
    val initialized: Boolean = false,
    val count: Int,
) {
    fun toSManga(): SManga = SMangaImpl().apply {
        url = this@RankedMangaCountDto.url
        title = this@RankedMangaCountDto.title
        artist = this@RankedMangaCountDto.artist
        author = this@RankedMangaCountDto.author
        description = this@RankedMangaCountDto.description
        genre = this@RankedMangaCountDto.genre
        status = this@RankedMangaCountDto.status
        thumbnail_url = this@RankedMangaCountDto.thumbnailUrl
        initialized = this@RankedMangaCountDto.initialized
    }

    companion object {
        fun from(manga: SManga, count: Int): RankedMangaCountDto = RankedMangaCountDto(
            url = manga.url,
            title = manga.title,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            status = manga.status,
            thumbnailUrl = manga.thumbnail_url,
            initialized = manga.initialized,
            count = count,
        )
    }
}
// KMK <--
