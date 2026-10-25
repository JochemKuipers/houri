package exh.recs

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.recs.batch.RankedSearchResults
import exh.recs.components.RecommendsScreen
import exh.recs.sources.RECOMMENDS_SOURCE
import exh.recs.sources.StaticResultPagingSource
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.SmartSearchSourcesRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.Serializable

/**
 * What the recommends screen should show. Formerly nested in the `RecommendsScreen` Voyager
 * screen class; kept as a top-level sealed interface so it can stay an assisted ViewModel
 * parameter without being a route payload (routes carry the serializable
 * [RankedSearchResultsDto] instead).
 */
sealed interface RecommendsScreenArgs : Serializable {
    data class SingleSourceManga(val mangaId: Long, val sourceId: Long) : RecommendsScreenArgs
    data class MergedSourceMangas(val mergedResults: List<RankedSearchResults>) : RecommendsScreenArgs
}

/** Recommendations for a single manga, computed by one of the recommendation sources. */
@Composable
fun RecommendsScreen(mangaId: Long, sourceId: Long) {
    RecommendsScreen(RecommendsScreenArgs.SingleSourceManga(mangaId, sourceId))
}

/** Batch recommendations gathered from the whole library (multi-source, ranked). */
@Composable
fun MergedRecommendsScreen(mergedResults: List<RankedSearchResultsDto>) {
    RecommendsScreen(
        RecommendsScreenArgs.MergedSourceMangas(mergedResults.map { it.toRankedSearchResults() }),
    )
}

@Composable
private fun RecommendsScreen(args: RecommendsScreenArgs) {
    if (!ifSourcesLoaded()) {
        LoadingScreen()
        return
    }

    val context = LocalContext.current
    val backStack = LocalBackStack.current

    val screenModel = assistedMetroViewModel<RecommendsViewModel, RecommendsViewModel.Factory> {
        create(args)
    }
    val state by screenModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteScreenModel.backHandler()
    }
    // KMK <--

    val onClickItem = { manga: Manga ->
        backStack.add(
            when (manga.source) {
                RECOMMENDS_SOURCE -> SmartSearchSourcesRoute(manga.ogTitle)
                else -> MangaRoute(manga.id, true)
            },
        )
    }

    val onLongClickItem = { manga: Manga ->
        when (manga.source) {
            RECOMMENDS_SOURCE -> WebViewActivity.newIntent(context, manga.url, title = manga.title).let(context::startActivity)
            else -> {
                // KMK -->
                // Add to favorite
                bulkFavoriteScreenModel.addRemoveManga(
                    manga,
                    haptic,
                )
                // KMK <--
            }
        }
    }

    RecommendsScreen(
        title = if (args is RecommendsScreenArgs.SingleSourceManga) {
            stringResource(SYMR.strings.similar, state.title.orEmpty())
        } else {
            stringResource(SYMR.strings.rec_common_recommendations)
        },
        state = state,
        navigateUp = backStack::removeLastOrNull,
        getManga = @Composable { manga: Manga -> screenModel.getManga(manga) },
        onClickSource = { pagingSource ->
            // Pass class name of paging source as routes need to be serializable
            backStack.add(
                when (args) {
                    is RecommendsScreenArgs.SingleSourceManga ->
                        BrowseRecommendsRoute(
                            args.mangaId,
                            args.sourceId,
                            pagingSource::class.qualifiedName!!,
                            pagingSource.associatedSourceId == null,
                        )
                    is RecommendsScreenArgs.MergedSourceMangas ->
                        BrowseMergedRecommendsRoute(
                            RankedSearchResultsDto.from((pagingSource as StaticResultPagingSource).data),
                            pagingSource.associatedSourceId == null,
                        )
                },
            )
        },
        onClickItem = { onClickItem(it) },
        onLongClickItem = { onLongClickItem(it) },
    )

    // KMK -->
    BulkFavoriteDialogs(
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}
