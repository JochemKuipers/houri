package mihon.core.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import mihon.core.concurrency.AppDispatchersHolder

/**
 * A variant of [viewModelScope] except with the IO dispatcher instead of the main dispatcher.
 *
 * Replaces the Voyager-era `ScreenModel.ioCoroutineScope`. The scope is cancelled when the
 * ViewModel is cleared (i.e. when its nav entry leaves the back stack), so heavy work keeps
 * running off the UI thread for exactly as long as the screen that owns it.
 *
 * Note that `viewModelScope` itself stays on the main dispatcher, matching Voyager's
 * `screenModelScope` — code that must leave the main thread keeps using `launchIO` /
 * `withIOContext` / this scope, never a blocking call on the composition path.
 */
val ViewModel.ioCoroutineScope: CoroutineScope
    get() = viewModelScope + AppDispatchersHolder.get().io
