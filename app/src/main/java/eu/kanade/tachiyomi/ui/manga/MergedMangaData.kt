package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference

// SY -->
/**
 * The merged-merge view model for a manga details screen: the reference rows, the child manga
 * keyed by manga id, and the resolved child sources keyed the same way.
 */
data class MergedMangaData(
    val references: List<MergedMangaReference>,
    val manga: Map<Long, Manga>,
    val sources: List<Source>,
)
// SY <--
