package eu.kanade.tachiyomi.ui.browse.source.browse

import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.model.EXHSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.sy.SYMR

/**
 * Saved-search subsystem of the browse screen, extracted from [BrowseSourceViewModel]:
 * reloading the EXH saved searches for the current source, opening/saving/deleting them.
 *
 * The VM's state types ([BrowseSourceViewModel.State], [BrowseSourceViewModel.Dialog],
 * [BrowseSourceViewModel.Listing]) are referenced through accessors so the controller stays
 * free of Android context and VM internals.
 */
internal class BrowseSavedSearchController(
    private val scope: CoroutineScope,
    private val getExhSavedSearch: GetExhSavedSearch,
    private val insertSavedSearch: InsertSavedSearch,
    private val deleteSavedSearchById: DeleteSavedSearchById,
    private val filterStore: BrowseSourceFilterStore,
    private val provideSource: () -> Source,
    private val provideState: () -> BrowseSourceViewModel.State,
    private val updateState: (transform: (BrowseSourceViewModel.State) -> BrowseSourceViewModel.State) -> Unit,
    private val setDialog: (BrowseSourceViewModel.Dialog?) -> Unit,
    private val resetFilters: () -> Unit,
) {
    internal fun reloadSavedSearches() {
        scope.launchIO {
            getExhSavedSearch.await(provideSource().id, provideSource()::getFilterList)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EXHSavedSearch::name))
                .let { savedSearches ->
                    updateState { it.copy(savedSearches = savedSearches.toImmutableList()) }
                }
        }
    }

    /** Show a dialog to enter name for new saved search */
    fun onSaveSearch() {
        scope.launchIO {
            val names = provideState().savedSearches.map { it.name }.toImmutableList()
            updateState { it.copy(dialog = BrowseSourceViewModel.Dialog.CreateSavedSearch(names)) }
        }
    }

    /** Open a saved search */
    fun onSavedSearch(
        loadedSearch: EXHSavedSearch,
        onToast: (StringResource) -> Unit,
    ) {
        resetFilters()
        scope.launchIO {
            val search = getExhSavedSearch.awaitOne(loadedSearch.id, provideSource()::getFilterList) ?: loadedSearch

            if (search.filterList == null && provideState().filters.isNotEmpty()) {
                withUIContext {
                    onToast(SYMR.strings.save_search_invalid)
                }
                return@launchIO
            }

            val allDefault = search.filterList != null && search.filterList == provideSource().getFilterList()
            setDialog(null)

            val filters: FilterList = search.filterList
                ?.takeUnless { allDefault }
                ?: provideSource().getFilterList()

            updateState {
                it.copy(
                    listing = BrowseSourceViewModel.Listing.Search(
                        query = search.query,
                        filters = filters,
                        savedSearchId = search.id,
                    ),
                    filters = filters,
                    toolbarQuery = search.query,
                )
            }
        }
    }

    /** Show dialog to delete saved search */
    fun onSavedSearchPress(search: EXHSavedSearch) {
        updateState { it.copy(dialog = BrowseSourceViewModel.Dialog.DeleteSavedSearch(search.id, search.name)) }
    }

    /** Save a search */
    fun saveSearch(
        name: String,
    ) {
        scope.launchNonCancellable {
            val query = provideState().toolbarQuery?.takeUnless {
                it.isBlank() || it == GetRemoteManga.QUERY_POPULAR || it == GetRemoteManga.QUERY_LATEST
            }?.trim()
            val filterList = provideState().filters.ifEmpty { provideSource().getFilterList() }
            insertSavedSearch.await(
                SavedSearch(
                    id = -1,
                    source = provideSource().id,
                    name = name.trim(),
                    query = query,
                    filtersJson = filterStore.serializeJson(filterList),
                ),
            )
        }
    }

    fun deleteSearch(savedSearchId: Long) {
        scope.launchNonCancellable {
            deleteSavedSearchById.await(savedSearchId)
        }
    }
}
