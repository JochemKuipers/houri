package exh.recs

import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.metadata.metadata.RaisedSearchMetadata
import exh.recs.sources.RECOMMENDS_SOURCE
import exh.recs.sources.RecommendationPagingSource
import exh.recs.sources.RecommendationSource
import exh.recs.sources.StaticResultPagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

@AssistedInject
class BrowseRecommendsViewModel(
    @Assisted private val args: BrowseRecommendsScreenArgs,
    private val getManga: GetManga = globalAppGraph.getManga,
) : BrowseSourceViewModel(
    sourceId = when (args) {
        is BrowseRecommendsScreenArgs.SingleSourceManga -> args.sourceId
        is BrowseRecommendsScreenArgs.MergedSourceMangas -> args.results.recAssociatedSourceId ?: RECOMMENDS_SOURCE
    },
    listingQuery = null,
    filtersJson = null,
    savedSearch = null,
) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(args: BrowseRecommendsScreenArgs): BrowseRecommendsViewModel
    }

    private var manga: Manga? = null

    init {
        viewModelScope.launchIO {
            manga = when (args) {
                is BrowseRecommendsScreenArgs.SingleSourceManga -> getManga.await(args.mangaId)
                is BrowseRecommendsScreenArgs.MergedSourceMangas -> null
            }
        }
    }

    val recommendationPagingSource: RecommendationPagingSource
        get() = when (args) {
            is BrowseRecommendsScreenArgs.MergedSourceMangas -> StaticResultPagingSource(args.results)
            is BrowseRecommendsScreenArgs.SingleSourceManga -> RecommendationPagingSource.createSources(
                manga ?: Manga.create(),
                // KMK -->
                RecommendationSource(sourceId),
                // KMK <--
            ).first {
                it::class.qualifiedName == args.recommendationSourceName
            }
        }

    override fun createSourcePagingSource(query: String, filters: FilterList) = recommendationPagingSource

    override fun Flow<Manga>.combineMetadata(metadata: RaisedSearchMetadata?): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        // Overridden to prevent our custom metadata from being replaced from a cache
        return flatMapLatest { manga -> flowOf(manga to metadata) }
    }

    init {
        mutableState.update { it.copy(filterable = false) }
    }
}
