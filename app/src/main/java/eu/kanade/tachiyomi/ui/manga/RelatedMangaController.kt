package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ResolvableSource
import exh.source.isMergedSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetSequelPrequel
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.SEQUEL_PREQUEL_STUB_MEMO_KEY
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.SequelPrequelEntry
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.track.model.Track


/**
 * Related-manga and sequel/prequel resolution subsystem of the manga details screen,
 * extracted from [MangaViewModel]:
 * fetching related mangas from the open source, resolving sequel/prequel entries to
 * openable manga ids (library first, then tracker stub, then in-source resolution).
 */
internal class RelatedMangaController(
    private val scope: CoroutineScope,
    private val uiPreferences: UiPreferences,
    private val sourcePreferences: SourcePreferences,
    private val trackPreferences: TrackPreferences,
    private val trackerManager: TrackerManager,
    private val mangaRepository: MangaRepository,
    private val getSequelPrequel: GetSequelPrequel,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val provideManga: () -> Manga?,
    private val provideSuccessState: () -> MangaViewModel.State.Success?,
    private val updateState: (transform: (MangaViewModel.State.Success) -> MangaViewModel.State.Success) -> Unit,
    private val showError: (Throwable) -> Unit,
) {
// KMK -->
/**
 * Set the fetching related mangas status.
 * @param state
 * - false: started & fetching
 * - true: finished
 */
private fun setRelatedMangasFetchedStatus(state: Boolean) {
    updateState { it.copy(isRelatedMangasFetched = state) }
}

/**
 * Requests an list of related mangas from the source.
 */
internal suspend fun fetchRelatedMangasFromSource(onDemand: Boolean = false, onFinish: (() -> Unit)? = null) {
    val expandRelatedMangas = uiPreferences.expandRelatedMangas().get()
    if ((!onDemand && !expandRelatedMangas) || provideManga()?.let { isMergedSourceId(it.source) } == true) return

    // start fetching related mangas
    setRelatedMangasFetchedStatus(false)

    fun exceptionHandler(e: Throwable) {
        logcat(LogPriority.ERROR, e)
        showError(e)

        viewModelScope.launch {
            Unit
        }
    }
    val state = provideSuccessState() ?: return
    val relatedMangasEnabled = sourcePreferences.relatedMangas().get()

    try {
        if (state.source !is StubSource && relatedMangasEnabled) {
            state.source.getRelatedMangaList(state.manga.toSManga(), { e -> exceptionHandler(e) }) { pair, _ ->
                /* Push found related mangas into collection */
                val relatedManga = RelatedManga.Success.fromPair(pair) { mangaList ->
                    mangaList
                        .map { it.toDomainManga(state.source.id) }
                        .distinctBy { it.url }
                        .let { networkToLocalManga(manga = it, updateInfo = false) }
                }

                updateState { successState ->
                    val relatedMangaCollection =
                        successState.relatedMangaCollection
                            ?.toMutableStateList()
                            ?.apply { add(relatedManga) }
                            ?: listOf(relatedManga)
                    successState.copy(relatedMangaCollection = relatedMangaCollection)
                }
            }
        }
    } catch (e: Exception) {
        exceptionHandler(e)
    } finally {
        if (onFinish != null) {
            onFinish()
        } else {
            setRelatedMangasFetchedStatus(true)
        }
    }
}
// KMK <--

// KMK -->
internal suspend fun fetchSequelPrequel() {
    val state = provideSuccessState() ?: return
    if (!uiPreferences.showSequelPrequel().get()) return
    try {
        val preferredTrackerId = trackPreferences.getPreferredTrackerForManga(state.manga.id)
        when (val result = getSequelPrequel.await(state.manga.id, preferredTrackerId, enabled = true)) {
            is GetSequelPrequel.Result.Success -> {
                // KMK --> scan the library once so stub entries matching a
                // library title render an "In library" badge. Stubs stay
                // non-favorite (insertNetworkManga preserves favorite=false)
                // so they never count towards library/achievement stats.
                // Titles come from the two-column projection, not the whole
                // libraryView aggregate: this runs per details screen.
                val libraryTitles = try {
                    mangaRepository.getFavoriteTitles()
                        .map(String::lowercase)
                        .toSet()
                } catch (_: Exception) {
                    emptySet()
                }
                // KMK <--
                updateState { it.copy(sequelPrequelEntries = result.entries, sequelPrequelLibraryTitles = libraryTitles) }
            }
            GetSequelPrequel.Result.Hidden, GetSequelPrequel.Result.Disabled ->
                updateState { it.copy(sequelPrequelEntries = emptyList()) }
        }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
        updateState { it.copy(sequelPrequelEntries = emptyList()) }
    }
}

suspend fun resolveSequelPrequel(entry: SequelPrequelEntry): Long? {
    val state = provideSuccessState() ?: return null
    // KMK --> library first: land on the existing library entry without
    // creating a stub or hitting any network. One indexed title lookup
    // instead of materializing every library entry with its chapter counts.
    try {
        mangaRepository.getMangaIdByTitle(entry.title)?.let { return it }
    } catch (_: Exception) {
    }
    // KMK --> tracker stub: metadata comes from the tracker that reported
    // the relation, since trackers carry no readable chapters.
    try {
        resolveTrackerStub(entry, state.source.id)?.let { return it }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
    }
    // KMK --> in-source resolution (e.g. MangaDex fallback urls).
    return try {
        val sManga = (state.source as? ResolvableSource)?.getManga(entry.url)
            ?: state.source.getMangaUpdate(
                SManga.create().apply {
                    url = entry.url
                    title = entry.title
                },
                emptyList(),
                fetchDetails = true,
                fetchChapters = false,
            ).manga
        val resolved = networkToLocalManga(sManga.toDomainManga(state.source.id))
        // KMK --> library scan first: land on the existing library entry
        // instead of opening a duplicate stub. Favoriting the stub still
        // raises the DuplicateManga dialog with a migrate prompt via
        // toggleFavorite(checkDuplicate = true).
        val duplicates = try {
            getDuplicateLibraryManga(resolved)
        } catch (_: Exception) {
            emptyList()
        }
        duplicates.firstOrNull { it.manga.favorite }?.manga?.id
            ?: duplicates.firstOrNull()?.manga?.id
            ?: resolved.id
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
        null
    }
}

private suspend fun resolveTrackerStub(entry: SequelPrequelEntry, sourceId: Long): Long? {
    val trackerId = entry.trackerId ?: return null
    val service = trackerManager.get(trackerId) as? BaseTracker ?: return null
    if (!service.isLoggedIn) return null
    val remoteId = service.parseRelatedEntryId(entry.url) ?: return null
    val metadata = service.getMangaMetadata(
        Track(
            id = -1L,
            mangaId = -1L,
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = null,
            title = entry.title,
            lastChapterRead = 0.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = entry.url,
            startDate = 0L,
            finishDate = 0L,
            private = false,
        ),
    )
    val sManga = SManga.create().apply {
        url = entry.url
        title = metadata.title?.ifBlank { null } ?: entry.title
        thumbnail_url = metadata.thumbnailUrl?.ifBlank { null } ?: entry.coverUrl
        description = metadata.description
        author = metadata.authors
        artist = metadata.artists
        genre = metadata.tags?.joinToString(", ")
        status = metadata.status?.toInt() ?: SManga.UNKNOWN
        initialized = true
        memo = buildJsonObject { put(SEQUEL_PREQUEL_STUB_MEMO_KEY, true) }
    }
    val resolved = networkToLocalManga(sManga.toDomainManga(sourceId))
    val duplicates = try {
        getDuplicateLibraryManga(resolved)
    } catch (_: Exception) {
        emptyList()
    }
    return duplicates.firstOrNull { it.manga.favorite }?.manga?.id
        ?: duplicates.firstOrNull()?.manga?.id
        ?: resolved.id
}
// KMK <--
}
