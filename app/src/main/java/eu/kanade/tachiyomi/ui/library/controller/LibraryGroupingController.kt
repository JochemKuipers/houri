package eu.kanade.tachiyomi.ui.library.controller

import android.content.Context
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapNotNull
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import tachiyomi.domain.category.service.CategoryTreeHandler
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.local.LocalSource

/**
 * Builds the library's category groups, extracted from [eu.kanade.tachiyomi.ui.library.LibraryViewModel].
 *
 * BY_DEFAULT groups by the real category tree (subcategories roll up into their root, which is
 * what a tab shows); UNGROUPED is one flat pseudo-category; BY_TRACK_STATUS / BY_SOURCE /
 * BY_STATUS synthesize pseudo-categories from tracking state, source, or manga status.
 */
class LibraryGroupingController(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
) {
    suspend fun group(
        items: List<LibraryItem>,
        categories: List<Category>,
        // KMK -->
        groupType: Int,
        showHiddenCategories: Boolean,
        activeCategoryId: Long?,
        activeSubCategoryId: Long?,
        // KMK <--
        context: Context,
    ): Map<Category, List</* LibraryItem */ Long>> {
        return when (groupType) {
            LibraryGroup.BY_DEFAULT -> {
                groupByDefault(items, categories, showHiddenCategories, activeCategoryId, activeSubCategoryId)
            }
            LibraryGroup.UNGROUPED -> {
                mapOf(
                    Category(
                        0,
                        context.stringResource(SYMR.strings.ungrouped),
                        0,
                        0,
                        // KMK -->
                        false,
                        // KMK <--
                    ) to
                        items.map { it.id },
                )
            }

            else -> {
                getGroupedMangaItems(
                    items = items,
                    groupType = groupType,
                    context = context,
                )
            }
        }
        // KMK <--
    }

    /**
     * The default grouping: one tab per top-level category, containing the ids of every
     * manga in its tree (or, when a subcategory is open, just that subcategory's ids).
     */
    private fun groupByDefault(
        items: List<LibraryItem>,
        categories: List<Category>,
        showHiddenCategories: Boolean,
        activeCategoryId: Long?,
        activeSubCategoryId: Long?,
    ): Map<Category, List</* LibraryItem */ Long>> {
        var showSystemCategory = false
        val categoriesById = categories.associateBy { it.id }
        val directCache = mutableMapOf</* Category.id */ Long, MutableList</* LibraryItem */ Long>>()
        val treeCache = mutableMapOf</* Root category.id */ Long, MutableList</* LibraryItem */ Long>>()
        items.forEach { item ->
            item.libraryManga.categories.forEach { categoryId ->
                if (categoryId == UNCATEGORIZED_ID) {
                    showSystemCategory = true
                }
                directCache.getOrPut(categoryId) { mutableListOf() }.add(item.id)
                val rootId = categoriesById[categoryId]?.let { cat ->
                    if (cat.parentId == 0L) cat.id else CategoryTreeHandler.rootIdFor(cat, categoriesById)
                } ?: categoryId
                treeCache.getOrPut(rootId) { mutableListOf() }.add(item.id)
            }
        }
        // Only top-level categories are rendered as library tabs; subcategories are
        // accessed through the subcategory row inside their parent category
        return categories.fastFilter {
            (showSystemCategory || !it.isSystemCategory) &&
                (showHiddenCategories || !it.hidden) &&
                it.parentId == 0L
        }
            .associateWith { category ->
                if (category.id == activeCategoryId && activeSubCategoryId != null) {
                    directCache[activeSubCategoryId].orEmpty()
                } else {
                    treeCache[category.id].orEmpty()
                }
                    .distinct()
            }
    }

    // SY -->
    private suspend fun getGroupedMangaItems(
        items: List<LibraryItem>,
        groupType: Int,
        context: Context,
    ): Map<Category, List</* LibraryItem */ Long>> {
        return when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                val tracks = getTracks.await().groupBy { it.mangaId }
                // KMK -->
                val groupCache = mutableMapOf</* Track.status */ Int, MutableList</* LibraryItem */ Long>>()
                items.forEach { item ->
                    val statuses = tracks[item.libraryManga.manga.id]?.fastMapNotNull { track ->
                        TrackStatus.parseTrackerStatus(trackerManager, track.trackerId, track.status)
                    }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(TrackStatus.OTHER)
                    statuses.forEach { status ->
                        groupCache.getOrPut(status.int) { mutableListOf() }.add(item.id)
                    }
                }
                // KMK <--
                groupCache.mapKeys { (id) ->
                    // KMK -->
                    val trackStatus = TrackStatus.entries.find { it.int == id } ?: TrackStatus.OTHER
                    // KMK <--
                    Category(
                        id = id.toLong(),
                        // KMK -->
                        name = context.stringResource(trackStatus.res),
                        order = trackStatus.ordinal.toLong(),
                        // KMK <--
                        flags = 0,
                        // KMK -->
                        hidden = false,
                        // KMK <--
                    )
                }
                    // KMK -->
                    .mapValues { (_, values) -> values.distinct() }
                // KMK <--
            }
            LibraryGroup.BY_SOURCE -> {
                // KMK -->
                val groupCache = mutableMapOf</* Source.id */ Long, MutableList</* LibraryItem */ Long>>()
                items.forEach { item ->
                    groupCache.getOrPut(item.libraryManga.manga.source) { mutableListOf() }.add(item.id)
                }
                val sources = groupCache.keys
                    .map { sourceManager.getOrStub(it) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id.toString() } })
                val sourceOrderMap = sources.withIndex().associate { (index, source) -> source.id to index.toLong() }

                sources.associate {
                    val categoryId = if (it.id == LocalSource.ID) -2L else it.id
                    val category = Category(
                        id = categoryId,
                        name = if (it.id == LocalSource.ID) {
                            context.stringResource(MR.strings.local_source)
                        } else {
                            it.name.ifBlank { it.id.toString() }
                        },
                        order = sourceOrderMap[it.id] ?: Long.MAX_VALUE,
                        flags = 0,
                        // KMK -->
                        hidden = false,
                        // KMK <--
                    )
                    category to groupCache[it.id].orEmpty()
                }
                // KMK <--
            }
            LibraryGroup.BY_STATUS -> {
                items.groupBy { item ->
                    item.libraryManga.manga.status
                }.mapKeys {
                    // KMK -->
                    val (nameRes, order) = statusMap[it.key] ?: (MR.strings.unknown to 7L)
                    // KMK <--
                    Category(
                        id = it.key + 1,
                        name = context.stringResource(nameRes),
                        order = order,
                        flags = 0,
                        // KMK -->
                        hidden = false,
                        // KMK <--
                    )
                }
                    // KMK -->
                    .mapValues { (_, libraryItem) -> libraryItem.fastMap { it.id } }
                // KMK <--
            }
            else -> emptyMap()
        }.toSortedMap(compareBy { it.order })
    }

    // KMK -->
    private val statusMap = mapOf(
        SManga.ONGOING.toLong() to (MR.strings.ongoing to 1L),
        SManga.COMPLETED.toLong() to (MR.strings.completed to 2L),
        SManga.PUBLISHING_FINISHED.toLong() to (MR.strings.publishing_finished to 3L),
        SManga.LICENSED.toLong() to (MR.strings.licensed to 4L),
        SManga.ON_HIATUS.toLong() to (MR.strings.on_hiatus to 5L),
        SManga.CANCELLED.toLong() to (MR.strings.cancelled to 6L),
    )
    // KMK <--
    // SY <--
}
