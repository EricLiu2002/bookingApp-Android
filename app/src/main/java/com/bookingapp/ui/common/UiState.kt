package com.bookingapp.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What a screen shows while it reads from the ledger. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

/** Re-runs a flow on demand, for the "Retry" action. */
class RetryTrigger {
    private val requests = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    fun retry() {
        requests.tryEmit(Unit)
    }

    /** Loading, then Content for each value, or Error if the source fails; restarts on [retry]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> wrap(errorMessage: String, source: () -> Flow<T>): Flow<UiState<T>> = requests.flatMapLatest {
        source()
            .map<T, UiState<T>> { UiState.Content(it) }
            .onStart { emit(UiState.Loading) }
            .catch { emit(UiState.Error(errorMessage)) }
    }
}
