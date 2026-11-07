package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.model.PagePreview

// SY -->
sealed interface PagePreviewState {
    data object Unused : PagePreviewState
    data object Loading : PagePreviewState
    data class Success(val pagePreviews: List<PagePreview>) : PagePreviewState
    data class Error(val error: Throwable) : PagePreviewState
}
// SY <--
