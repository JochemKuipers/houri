package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.Source
import xyz.nulldev.ts.api.http.serializer.FilterSerializer

/**
 * Source-level filter persistence for [BrowseSourceViewModel], extracted from the VM (SY block):
 * loads the persisted filter tree for this source (per-sort first, then the base store),
 * and writes it back on browse without a saved search attached.
 */
internal class BrowseSourceFilterStore(
    private val provideSourceId: () -> Long,
    private val provideSource: () -> Source,
    private val libraryPreferences: LibraryPreferences,
) {
    private val filterSerializer = FilterSerializer()

    fun loadSavedFilterList(): FilterList? {
        if (!libraryPreferences.sourceLevelFiltering().get()) return null
        try {
            val source = provideSource()
            val defaultList = source.getFilterList()
            val sortIndex = defaultList.filterIsInstance<SourceModelFilter.Sort>().firstOrNull()?.state?.index
            if (sortIndex != null) {
                val perSortJson = libraryPreferences.sourceFilterJsonForSort(provideSourceId(), sortIndex).get()
                if (perSortJson.isNotBlank()) {
                    val arr = Json.decodeFromString<JsonArray>(perSortJson)
                    val list = source.getFilterList()
                    filterSerializer.deserialize(list, arr)
                    return list
                }
            }
        } catch (_: Exception) {
        }
        val baseJson = libraryPreferences.sourceFilterJson(provideSourceId()).get()
        if (baseJson.isBlank()) return null
        return try {
            val arr = Json.decodeFromString<JsonArray>(baseJson)
            val list = provideSource().getFilterList()
            filterSerializer.deserialize(list, arr)
            list
        } catch (_: Exception) {
            null
        }
    }

    fun persistFilterList(filters: FilterList) {
        if (!libraryPreferences.sourceLevelFiltering().get()) return
        try {
            val json = filterSerializer.serialize(filters)
            val str = Json.encodeToString(json)
            libraryPreferences.setSourceFilterJson(provideSourceId(), str)
            val sort = filters.filterIsInstance<SourceModelFilter.Sort>().firstOrNull()?.state
            if (sort != null) libraryPreferences.setSourceFilterJsonForSort(provideSourceId(), sort.index, str)
        } catch (_: Exception) {
        }
    }

    /** Deserializes [json] into [list] (fresh private filter tree; publish it atomically after). */
    fun deserializeInto(list: FilterList, json: JsonArray) {
        filterSerializer.deserialize(list, json)
    }

    /** Serializes [filters] to the saved-search JSON string, or null when empty/unserializable. */
    fun serializeJson(filters: FilterList): String? = runCatching {
        filterSerializer.serialize(filters).ifEmpty { null }?.let { Json.encodeToString(it) }
    }.getOrNull()
}
