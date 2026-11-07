package eu.kanade.tachiyomi.ui.manga

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.domain.chapter.model.Chapter

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
        // SY -->
        val sourceName: String?,
        val showScanlator: Boolean,
        // SY <--
        val translationProgress: Int = 0,
        val isTranslating: Boolean = false,
        val isTranslationError: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
