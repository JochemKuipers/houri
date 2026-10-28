package eu.kanade.tachiyomi.extension

import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.CancellationException
import mihon.app.di.globalAppGraph
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource

/**
 * Runs a source operation, reporting any failure to the extension watchdog.
 *
 * Failures from an extension are nearly always a site having changed underneath a scraper, which
 * only that extension's own maintainers can fix. Every source call site swallows its exception and
 * shows a generic error, so without this the user cannot tell whose problem it is.
 *
 * A success clears the extension's recorded failure, since the far likelier reading of a source that
 * answers now is that whatever broke it has since been fixed.
 */
suspend inline fun <T> reportingSourceErrors(
    source: Source?,
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block()).onSuccess {
        globalAppGraph.extensionManager.clearSourceError(source)
    }
} catch (e: Throwable) {
    if (e is CancellationException) throw e
    globalAppGraph.extensionManager.reportSourceError(source, e)
    Result.failure(e)
}

/**
 * [reportingSourceErrors] for an operation that signals failure by returning null, so callers keep
 * their existing null-handling instead of unwrapping a [Result].
 *
 * Null is not taken as a recovery: it means this call produced nothing, which is not evidence that
 * the extension is healthy, so a recorded failure is left standing until a call actually returns.
 */
suspend inline fun <T : Any> reportingSourceErrorsOrNull(
    source: Source?,
    crossinline block: suspend () -> T?,
): T? = try {
    block()?.also { globalAppGraph.extensionManager.clearSourceError(source) }
} catch (e: Throwable) {
    if (e is CancellationException) throw e
    globalAppGraph.extensionManager.reportSourceError(source, e)
    null
}

/**
 * Wraps a browse listing so a page load that fails reaches the extension watchdog.
 *
 * The paging sources live in the data module, which cannot see the watchdog, so this wraps the
 * instance where the screen creates it - the layer that knows both the source and the reporter.
 * Browse is the most direct way a user touches one extension, and before this its failures
 * stopped at the screen's own error state: the extensions page never learned why a source
 * returned nothing until the user read a chapter or downloaded one.
 *
 * [NoResultsException] is passed through without reporting: an empty listing is an answer, not
 * a fault. Everything else a browse page can throw is attributed to the extension through the
 * same path the reader and the downloader use; sources that are not extensions (local, merged,
 * built-in, or stubs for uninstalled ones) resolve to no package there and are dropped.
 */
fun SourcePagingSource.reportingSourceLoadErrors(source: Source): SourcePagingSource =
    ReportingSourcePagingSource(this, source)

private class ReportingSourcePagingSource(
    private val delegate: SourcePagingSource,
    private val source: Source,
) : SourcePagingSource() {

    // The load types are nested in PagingSource; spelled with their outer class so they cannot
    // be confused with the extension loader's own LoadResult in extension.model, or with
    // LoadState.Error whose throwable goes by a different name.
    override suspend fun load(
        params: PagingSource.LoadParams<Long>,
    ): PagingSource.LoadResult<Long, Pair<Manga, RaisedSearchMetadata?>> {
        val result = delegate.load(params)
        if (result is PagingSource.LoadResult.Error<*, *> && result.throwable !is NoResultsException) {
            globalAppGraph.extensionManager.reportSourceError(source, result.throwable)
        }
        return result
    }

    override fun getRefreshKey(state: PagingState<Long, Pair<Manga, RaisedSearchMetadata?>>): Long? =
        delegate.getRefreshKey(state)
}
