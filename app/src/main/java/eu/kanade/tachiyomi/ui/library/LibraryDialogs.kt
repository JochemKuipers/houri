package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga

/**
 * The dialogs [eu.kanade.tachiyomi.ui.library.LibraryViewModel] can open, extracted from the
 * ViewModel body so the state type and its payloads live in one readable place.
 */
@Immutable
sealed interface LibraryDialog {
    data object SettingsSheet : LibraryDialog
    data class ChangeCategory(
        val manga: List<Manga>,
        val initialSelection: ImmutableList<CheckboxState<Category>>,
    ) : LibraryDialog
    data class DeleteManga(val manga: List<Manga>) : LibraryDialog

    // KMK -->
    data class CreateSubcategory(val artist: String?, val author: String?) : LibraryDialog
    // KMK <--

    // SY -->
    data object SyncFavoritesWarning : LibraryDialog
    data object SyncFavoritesConfirm : LibraryDialog
    data class RecommendationSearchSheet(val manga: List<Manga>) : LibraryDialog
    // SY <--
}

/**
 * Builds the checkbox preselection for the change-category dialog over the current selection:
 * CHECKED for categories every selected manga shares, EXCLUDE (partial) for categories some
 * of them are in, NONE otherwise.
 */
suspend fun changeCategoryPreselection(
    mangaList: List<Manga>,
    categories: List<Category>,
    categoryIdsOf: suspend (Manga) -> Set<Long>,
): ImmutableList<CheckboxState<Category>> {
    val mangaCategoryIds = mangaList.map { categoryIdsOf(it) }
    val common = mangaCategoryIds.reduceOrNull { set1, set2 -> set1 intersect set2 }.orEmpty()
    val mix = mangaCategoryIds.flatten().toSet() - common
    return categories
        .fastMap {
            when (it.id) {
                in common -> CheckboxState.State.Checked(it)
                in mix -> CheckboxState.TriState.Exclude(it)
                else -> CheckboxState.State.None(it)
            }
        }
        .toImmutableList()
}

/**
 * Only answers when every entry agrees on [selector], so a mixed selection falls back to
 * null and the dialog pre-fills a plain name instead of guessing.
 */
fun List<Manga>.sharedMetadata(selector: (Manga) -> String?): String? =
    mapNotNull(selector)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .singleOrNull()
