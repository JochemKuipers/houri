package mihon.core.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AndroidX ViewModel replacement for Voyager's `StateScreenModel`, keeping the same
 * `mutableState`/`state` shape so screen models migrate without touching their state logic.
 */
abstract class StateViewModel<S>(initialState: S) : ViewModel() {
    protected val mutableState: MutableStateFlow<S> = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()
}
