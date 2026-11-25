package com.bookingapp.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.reload.FileTooLargeException
import com.bookingapp.data.reload.LedgerFileSummary
import com.bookingapp.data.reload.LedgerFileValidator
import com.bookingapp.data.reload.LedgerReplacer
import com.bookingapp.data.reload.Rejection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

sealed interface ReloadState {
    data object Idle : ReloadState
    data object Checking : ReloadState
    data class Rejected(val message: String) : ReloadState
    data class Confirm(val summary: LedgerFileSummary) : ReloadState
    data object ConfirmRestore : ReloadState
    data object Working : ReloadState
    data class Failed(val message: String) : ReloadState
}

/** The "Replace ledger with a database file" section (design decision 15). */
class LedgerReloadViewModel(
    private val replacer: LedgerReplacer,
    private val documents: DocumentReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<ReloadState>(ReloadState.Idle)
    val state: StateFlow<ReloadState> = _state.asStateFlow()

    val hasBackup: StateFlow<Boolean> = replacer.hasBackup

    private val done = Channel<String>(Channel.BUFFERED)

    /** Emitted after a replace or restore: the message to show on the Dashboard. */
    val finished: Flow<String> = done.receiveAsFlow()

    fun onDatabasePicked(uri: Uri) {
        _state.value = ReloadState.Checking
        viewModelScope.launch {
            _state.value = try {
                val result = withContext(io) {
                    replacer.copyIn(documents.open(uri))
                    replacer.check()
                }
                when (result) {
                    is LedgerFileValidator.Result.Valid -> ReloadState.Confirm(result.summary)
                    is LedgerFileValidator.Result.Rejected -> {
                        withContext(io) { replacer.discardIncoming() }
                        ReloadState.Rejected(messageFor(result.reason))
                    }
                }
            } catch (e: FileTooLargeException) {
                ReloadState.Rejected(messageFor(Rejection.TooLarge))
            } catch (e: Exception) {
                ReloadState.Rejected("The file could not be read.")
            }
        }
    }

    fun confirmReplace() {
        val confirm = _state.value as? ReloadState.Confirm ?: return
        _state.value = ReloadState.Working
        viewModelScope.launch {
            try {
                withContext(io) { replacer.replace() }
                _state.value = ReloadState.Idle
                done.send("Ledger replaced: ${NumberFormat.getIntegerInstance(Locale.US).format(confirm.summary.transactionCount)} transactions")
            } catch (e: Exception) {
                withContext(io) { replacer.discardIncoming() }
                _state.value = ReloadState.Failed("The ledger could not be replaced. Your phone data is unchanged.")
            }
        }
    }

    fun cancel() {
        _state.value = ReloadState.Idle
        viewModelScope.launch { withContext(io) { replacer.discardIncoming() } }
    }

    fun askRestore() {
        _state.value = ReloadState.ConfirmRestore
    }

    fun confirmRestore() {
        _state.value = ReloadState.Working
        viewModelScope.launch {
            try {
                withContext(io) { replacer.restorePrevious() }
                _state.value = ReloadState.Idle
                done.send("Previous ledger restored")
            } catch (e: Exception) {
                _state.value = ReloadState.Failed("The previous ledger could not be restored. Your phone data is unchanged.")
            }
        }
    }

    companion object {
        fun messageFor(reason: Rejection): String = when (reason) {
            Rejection.TooLarge -> "The file is larger than 100 MB, so it is not a BookingApp database."
            Rejection.NotSqlite -> "This file is not a BookingApp database."
            Rejection.Corrupt -> "The database file is damaged. Stop the backend and copy bookingapp.db again."
            Rejection.SchemaMismatch -> "This database's tables don't match the BookingApp ledger, so it is not a BookingApp database this app supports."
            is Rejection.NewerSchema -> "This database comes from a newer BookingApp backend (migration ${reason.version}). Update the app first."
            Rejection.BrokenCategoryLink -> "The database has transactions that refer to missing categories."
        }
    }
}
