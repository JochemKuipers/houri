package eu.kanade.tachiyomi.ui.manga.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.manga.MangaNotesScreen
import kotlinx.coroutines.flow.update
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga

@Composable
fun MangaNotesScreen(
    manga: Manga,
) {
    val backStack = LocalBackStack.current

    val screenModel = assistedMetroViewModel<MangaNotesViewModel, MangaNotesViewModel.Factory> {
        create(manga)
    }
    val state by screenModel.state.collectAsState()

    MangaNotesScreen(
        state = state,
        navigateUp = backStack::removeLastOrNull,
        onUpdate = screenModel::updateNotes,
    )
}

@AssistedInject
class MangaNotesViewModel(
    @Assisted private val manga: Manga,
    private val updateMangaNotes: UpdateMangaNotes,
) : StateViewModel<State>(State(manga, manga.notes)) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(manga: Manga): MangaNotesViewModel
    }

    fun updateNotes(content: String) {
        if (content == state.value.notes) return

        mutableState.update {
            it.copy(notes = content)
        }

        viewModelScope.launchNonCancellable {
            updateMangaNotes(manga.id, content)
        }
    }
}

@Immutable
data class State(
    val manga: Manga,
    val notes: String,
)
