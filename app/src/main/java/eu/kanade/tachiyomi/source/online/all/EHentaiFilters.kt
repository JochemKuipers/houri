package eu.kanade.tachiyomi.source.online.all

import android.net.Uri
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import exh.eh.EHTags
import exh.log.xLogD
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_CENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_DECENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_FULL
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_MOSAIC
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.CENSORSHIP_STATUS_UNCENSORED
import exh.metadata.metadata.EHentaiSearchMetadata.Companion.EH_CENSORSHIP_NAMESPACE
import exh.util.UriFilter
import exh.util.UriGroup
import exh.util.dropBlank
import exh.util.trimAll

internal class Watched(val isEnabled: Boolean) : Filter.CheckBox("Watched List", isEnabled), UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendPath("watched")
        }
    }
}

enum class ToplistOption(val humanName: String, val index: Int) {
    NONE("None", 0),
    ALL_TIME("All time", 11),
    PAST_YEAR("Past year", 12),
    PAST_MONTH("Past month", 13),
    YESTERDAY("Yesterday", 15),
    ;

    override fun toString(): String {
        return humanName
    }
}

internal class ToplistOptions : Filter.Select<ToplistOption>(
    "Toplists",
    ToplistOption.entries.toTypedArray(),
)

internal class GenreOption(name: String, val genreId: Int) : Filter.CheckBox(name, false)
internal class GenreGroup :
    Filter.Group<GenreOption>(
        "Genres",
        listOf(
            GenreOption("Dōjinshi", 2),
            GenreOption("Manga", 4),
            GenreOption("Artist CG", 8),
            GenreOption("Game CG", 16),
            GenreOption("Western", 512),
            GenreOption("Non-H", 256),
            GenreOption("Image Set", 32),
            GenreOption("Cosplay", 64),
            GenreOption("Asian Porn", 128),
            GenreOption("Misc", 1),
        ),
    ),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        val bits = state.fold(0) { acc, genre ->
            if (!genre.state) acc + genre.genreId else acc
        }
        builder.appendQueryParameter("f_cats", bits.toString())
    }
}

internal class CensorshipFilter : Filter.Select<String>(
    "Censorship",
    arrayOf("All", "Censored", "Decensored", "Mosaic", "Full", "Uncensored"),
) {
    fun query(): String = when (state) {
        1 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_CENSORED"
        2 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_DECENSORED"
        3 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_MOSAIC"
        4 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_FULL"
        5 -> "$EH_CENSORSHIP_NAMESPACE:$CENSORSHIP_STATUS_UNCENSORED"
        else -> ""
    }
}

internal class CensorshipSort : Filter.Sort(
    "Sort by Censorship",
    arrayOf("Default", "Censorship status"),
    Filter.Sort.Selection(0, true),
)

internal class AdvancedOption(
    name: String,
    val param: String,
    defValue: Boolean = false,
) : Filter.CheckBox(name, defValue), UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) {
            builder.appendQueryParameter(param, "on")
        }
    }
}

internal open class PageOption(name: String, private val queryKey: String) : Filter.Text(name), UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state.isNotBlank()) {
            if (builder.build().getQueryParameters("f_sp").isEmpty()) {
                builder.appendQueryParameter("f_sp", "on")
            }

            builder.appendQueryParameter(queryKey, state.trim())
        }
    }
}

internal object EHentaiFilters {
    fun combineQuery(filters: FilterList): String {
        val stringBuilder = StringBuilder()
        val advSearch = filters.filterIsInstance<Filter.AutoComplete>().flatMap { filter ->
            filter.state.trimAll().dropBlank().mapNotNull { tag ->
                val split = tag.split(":").filterNot { it.isBlank() }
                if (split.size > 1) {
                    val namespace = split[0].removePrefix("-").removePrefix("~")
                    val exclude = split[0].startsWith("-")
                    val or = split[0].startsWith("~")

                    AdvSearchEntry(namespace to split[1], exclude, or)
                } else if (split.size == 1) {
                    val item = split.first()
                    val exclude = item.startsWith("-")
                    val or = item.startsWith("~")
                    AdvSearchEntry(null to item, exclude, or)
                } else {
                    null
                }
            }
        }

        advSearch.forEach { entry ->
            if (entry.exclude) stringBuilder.append("-")
            if (entry.or) stringBuilder.append("~")
            val namespace = entry.search.first?.let { "$it:" }.orEmpty()
            if (entry.search.second.contains(" ")) {
                stringBuilder.append(("""$namespace"${entry.search.second}$""""))
            } else {
                stringBuilder.append("$namespace${entry.search.second}$")
            }
            stringBuilder.append(" ")
        }

        return stringBuilder.toString().trim().also { xLogD(it) }
    }
}

internal data class AdvSearchEntry(val search: Pair<String?, String>, val exclude: Boolean, val or: Boolean)

internal class AutoCompleteTags :
    Filter.AutoComplete(
        name = "Tags",
        hint = "Search tags here (limit of 8)",
        values = EHTags.getNamespaces().map { "$it:" } + EHTags.getAllTags(),
        skipAutoFillTags = EHTags.getNamespaces().map { "$it:" },
        validPrefixes = listOf("-", "~"),
        state = emptyList(),
    )

internal class MinPagesOption : PageOption("Minimum Pages", "f_spf")
internal class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

internal class RatingOption :
    Filter.Select<String>(
        "Minimum Rating",
        arrayOf(
            "Any",
            "2 stars",
            "3 stars",
            "4 stars",
            "5 stars",
        ),
    ),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state > 0) {
            builder.appendQueryParameter("f_srdd", (state + 1).toString())
            builder.appendQueryParameter("f_sr", "on")
        }
    }
}

internal class AdvancedGroup : UriGroup<Filter<*>>(
    "Advanced Options",
    listOf(
        AdvancedOption("Browse Expunged Galleries", "f_sh"),
        AdvancedOption("Require Gallery Torrent", "f_sto"),
        RatingOption(),
        MinPagesOption(),
        MaxPagesOption(),
        AdvancedOption("Disable custom Language filters", "f_sfl"),
        AdvancedOption("Disable custom Uploader filters", "f_sfu"),
        AdvancedOption("Disable custom Tag filters", "f_sft"),
    ),
)

internal class ReverseFilter : Filter.CheckBox("Reverse search results")

internal class JumpSeekFilter : Filter.Text("Jump/Seek")
