package eu.kanade.tachiyomi.ui.manga


import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSearchEventKey
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSearchGenreEventKey
import eu.kanade.tachiyomi.ui.home.LibrarySearchEventKey
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import exh.recs.RecommendsRoute
import exh.source.isMergedSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import mihon.app.di.globalAppGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.LibrarySearchRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MetadataViewRoute
import mihon.core.navigation.PagePreviewRoute
import mihon.core.navigation.SmartSearchRoute
import mihon.core.navigation.SmartSearchSourcesRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.WebViewRoute
import mihon.core.navigation.util.popUntil
import mihon.core.navigation.util.replace
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR

internal fun continueReading(context: Context, unreadChapter: Chapter?) {
    if (unreadChapter != null) openChapter(context, unreadChapter)
}

@Volatile private var lastOpenChapterTime: Long = 0L

@Volatile private var lastMangaNavTime: Long = 0L
internal fun NavBackStack<NavKey>.safePush(route: NavKey) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 90) return
    lastMangaNavTime = now
    add(route)
}
internal fun NavBackStack<NavKey>.safeReplace(route: NavKey) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 90) return
    lastMangaNavTime = now
    replace(route)
}
internal fun openChapter(context: Context, chapter: Chapter) {
    val now = System.currentTimeMillis()
    if (now - lastOpenChapterTime < 200) return
    lastOpenChapterTime = now
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
}

@Suppress("LocalVariableName")
internal fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
    val manga = manga_ ?: return null
    val source = source_ as? HttpSource ?: return null

    return try {
        source.getMangaUrl(manga.toSManga())
    } catch (_: Exception) {
        null
    }
}

@Suppress("LocalVariableName")
internal fun openMangaInWebView(backStack: NavBackStack<NavKey>, manga_: Manga?, source_: Source?) {
    getMangaUrl(manga_, source_)?.let { url ->
        backStack.add(
            WebViewRoute(
                url = url,
                initialTitle = manga_?.title,
                sourceId = source_?.id,
            ),
        )
    }
}

@Suppress("LocalVariableName")
internal fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
    try {
        getMangaUrl(manga_, source_)?.let { url ->
            val intent = url.toUri().toShareIntent(context, type = "text/plain")
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        context.toast(e.message)
    }
}

/**
 * Perform a search using the provided query.
 *
 * @param query the search query to the parent controller
 */
internal suspend fun performSearch(
    backStack: NavBackStack<NavKey>,
    resultEventBus: ResultEventBus,
    query: String,
    global: Boolean,
    // KMK -->
    library: Boolean = false,
    // KMK <--
) {
    if (global) {
        backStack.add(GlobalSearchRoute(query))
        return
    }

    // KMK -->
    // Library search: route through a fresh home instance pushed on top of the current
    // screen, so Back from the library search results returns to the screen the search was
    // started from (#55) — the same behavior Global Search has. LibrarySearchRoute always
    // opens on the Library tab, where the query is applied via the result bus ->
    // LibraryTabSearchEventKey -> LibraryViewModel.search.
    if (library) {
        if (query.isBlank()) return
        if (backStack.lastOrNull() !is LibrarySearchRoute) {
            backStack.add(LibrarySearchRoute)
        }
        resultEventBus.sendResult(resultKey = LibrarySearchEventKey, result = query)
        return
    }
    // KMK <--

    if (backStack.size < 2) {
        return
    }

    // KMK -->
    backStack.popUntil { screen ->
        screen is HomeRoute ||
            (!library && (screen is BrowseSourceRoute || screen is SourceFeedRoute))
    }
    // KMK <--

    when (val previousRoute = backStack.lastOrNull()) {
        is HomeRoute -> resultEventBus.sendResult(resultKey = LibrarySearchEventKey, result = query)
        is BrowseSourceRoute -> resultEventBus.sendResult(resultKey = BrowseSearchEventKey, result = query)
        // SY -->
        is SourceFeedRoute -> {
            // KMK -->
            backStack.add(BrowseSourceRoute(previousRoute.sourceId, query))
            // KMK <--
        }
        // SY <--
    }
}

/**
 * Performs a genre search using the provided genre name.
 *
 * @param genreName the search genre to the parent controller
 */
internal suspend fun performGenreSearch(
    backStack: NavBackStack<NavKey>,
    resultEventBus: ResultEventBus,
    genreName: String,
    source: Source,
) {
    if (backStack.size < 2) {
        return
    }

    var idx = backStack.size - 2
    while (idx >= 0) {
        val previousRoute = backStack[idx--]
        if (previousRoute is BrowseSourceRoute && source is HttpSource) {
            // KMK -->
            backStack.popUntil { backStack.size == idx + 2 }
            // KMK <--
            resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
            return
        }
        // KMK -->
        if (previousRoute is SourceFeedRoute && source is HttpSource) {
            backStack.popUntil { backStack.size == idx + 2 }
            backStack.add(BrowseSourceRoute(previousRoute.sourceId, ""))
            resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
            return
        }
        // KMK <--
    }
    if (source is HttpSource) {
        backStack.add(BrowseSourceRoute(source.id, ""))
        resultEventBus.sendResult(resultKey = BrowseSearchGenreEventKey, result = genreName)
    } else {
        performSearch(backStack, resultEventBus, genreName, global = false)
    }
}

/**
 * Copy Manga URL to Clipboard
 */
@Suppress("LocalVariableName")
internal fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
    val manga = manga_ ?: return
    val source = source_ as? HttpSource ?: return
    val url = source.getMangaUrl(manga.toSManga())
    context.copyToClipboard(url, url)
}

// SY -->
internal fun openMetadataViewer(
    backStack: NavBackStack<NavKey>,
    manga: Manga,
    // KMK -->
    seedColor: Color?,
    // KMK <--
) {
    backStack.add(MetadataViewRoute(manga.id, manga.source, seedColor?.toArgb()))
}

internal fun mergedMangaAction(
    context: Context,
    backStack: NavBackStack<NavKey>,
    mergedMangaData: MergedMangaData,
    // KMK -->
    action: (Context, NavBackStack<NavKey>, Manga, HttpSource?) -> Unit,
    titleRes: StringResource,
    // KMK <--
) {
    val sourceManager: SourceManager = globalAppGraph.sourceManager
    // KMK -->
    val mergedMangaAndSources = mergedMangaData.manga.values
        .filterNot { isMergedSourceId(it.source) }
        .map { manga -> manga to sourceManager.getOrStub(manga.source) }
    // KMK <--
    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes.getString(context))
        .setSingleChoiceItems(
            Array(mergedMangaAndSources.size) { index ->
                // KMK -->
                mergedMangaAndSources[index].second.toString()
                // KMK <--
            },
            -1,
        ) { dialog, index ->
            dialog.dismiss()
            // KMK -->
            val (manga, source) = mergedMangaAndSources[index]
            action(context, backStack, manga, source as? HttpSource)
            // KMK <--
        }
        .setNegativeButton(MR.strings.action_cancel.getString(context), null)
        .show()
}

// KMK -->
internal fun browseSource(
    backStack: NavBackStack<NavKey>,
    smartSearchConfig: SmartSearchConfig?,
    source: Source,
    useNewSourceNavigation: Boolean,
) {
    val now = System.currentTimeMillis()
    if (now - lastMangaNavTime < 350) return
    lastMangaNavTime = now
    val route = when {
        // Clicked on source of an entry being merged with previous entry or
        // source of an recommending entry (to search again)
        smartSearchConfig != null -> SmartSearchRoute(
            sourceId = source.id,
            origTitle = smartSearchConfig.origTitle,
            origMangaId = smartSearchConfig.origMangaId,
        )
        useNewSourceNavigation -> SourceFeedRoute(source.id)
        else -> BrowseSourceRoute(source.id, GetRemoteManga.QUERY_POPULAR)
    }
    when (route) {
        // When doing a migrate/recommend => replace previous screen to perform search again.
        // KMK --> HomeRoute is the floor, as in MangaToolbar's onHomeClicked: without it a
        // stack holding none of the searched routes was popped to empty, and the add below
        // re-seeded with the route alone - rootless, and the next back press crashed NavDisplay.
        is SmartSearchRoute -> {
            backStack.popUntil { it is HomeRoute || it is SmartSearchRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }

        is SourceFeedRoute -> {
            backStack.popUntil { it is HomeRoute || it is SourceFeedRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }

        else -> {
            backStack.popUntil { it is HomeRoute || it is BrowseSourceRoute }
            if (backStack.size > 1) backStack.replace(route) else backStack.add(route)
        }
        // KMK <--
    }
}
// KMK <--

internal fun openMorePagePreviews(backStack: NavBackStack<NavKey>, manga: Manga) {
    backStack.safePush(PagePreviewRoute(manga.id))
}

internal fun openPagePreview(context: Context, chapter: Chapter?, page: Int) {
    chapter ?: return
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, page))
}
// SY <--

// EXH -->
/**
 * Called when click Merge on an entry to search for entries to merge.
 */
internal fun openSmartSearch(backStack: NavBackStack<NavKey>, manga: Manga) {
    val smartSearchConfig = SmartSearchConfig(manga.title, manga.id)
    backStack.safePush(SmartSearchSourcesRoute(smartSearchConfig.origTitle, smartSearchConfig.origMangaId))
}

@OptIn(DelicateCoroutinesApi::class)
internal fun mergeWithAnother(
    backStack: NavBackStack<NavKey>,
    context: Context,
    manga: Manga,
    smartSearchConfig: SmartSearchConfig?,
    smartSearchMerge: suspend (Manga, Long) -> Manga,
) {
    launchUI {
        try {
            val mergedManga = withNonCancellableContext {
                smartSearchMerge(manga, smartSearchConfig?.origMangaId!!)
            }

            backStack.popUntil { it is SmartSearchSourcesRoute }
            backStack.removeLastOrNull()
            // KMK -->
            if (backStack.lastOrNull() !is MangaRoute) {
                backStack.add(MangaRoute(mergedManga.id))
            } else {
                // KMK <--
                backStack.replace(MangaRoute(mergedManga.id))
            }
            context.toast(SYMR.strings.entry_merged)
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            context.toast(context.stringResource(SYMR.strings.failed_merge, e.message.orEmpty()))
        }
    }
}
// EXH <--

// AZ -->
internal fun openRecommends(backStack: NavBackStack<NavKey>, source: Source?, manga: Manga) {
    source ?: return
    backStack.safePush(RecommendsRoute(manga.id, source.id))
}
// AZ <--
