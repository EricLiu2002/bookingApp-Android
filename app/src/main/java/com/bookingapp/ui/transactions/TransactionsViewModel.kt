package com.bookingapp.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.TransactionRepository
import com.bookingapp.domain.Transaction
import com.bookingapp.domain.TransactionType
import com.bookingapp.ui.common.RetryTrigger
import com.bookingapp.ui.common.UiState
import com.bookingapp.ui.common.savedMonth
import com.bookingapp.ui.navigation.Transactions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.Clock
import java.time.YearMonth
import java.util.Locale

/** The type filter: one type, or null for All. */
enum class Filter(val type: TransactionType?, val label: String) {
    COSTS(TransactionType.COST, "Costs"),
    EARNINGS(TransactionType.EARNING, "Earnings"),
    ALL(null, "All"),
    ;

    companion object {
        fun fromRoute(value: String?): Filter = when (value) {
            TransactionType.EARNING.name -> EARNINGS
            Transactions.ALL -> ALL
            else -> COSTS
        }
    }
}

enum class SortField(val label: String) { DATE("Date"), CATEGORY("Category"), NOTE("Note"), TYPE("Type"), AMOUNT("Amount") }

data class Sort(val field: SortField = SortField.DATE, val ascending: Boolean = false)

class TransactionsViewModel(
    private val savedState: SavedStateHandle,
    private val repository: TransactionRepository,
    clock: Clock,
) : ViewModel() {

    private val retryTrigger = RetryTrigger()

    val month: StateFlow<YearMonth> = savedState.savedMonth(KEY_MONTH, clock, viewModelScope)

    val filter: StateFlow<Filter> = savedState.getStateFlow(KEY_TYPE, savedState.get<String>(KEY_TYPE))
        .map(Filter::fromRoute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Filter.fromRoute(savedState[KEY_TYPE]))

    val sort: StateFlow<Sort> = combine(
        savedState.getStateFlow(KEY_SORT_FIELD, SortField.DATE.name),
        savedState.getStateFlow(KEY_SORT_ASC, false),
    ) { field, asc -> Sort(SortField.valueOf(field), asc) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Sort(SortField.valueOf(savedState[KEY_SORT_FIELD] ?: "DATE"), savedState[KEY_SORT_ASC] ?: false))

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<List<Transaction>>> = retryTrigger.wrap("The transactions could not be loaded.") {
        combine(month, filter, ::Pair).flatMapLatest { (m, f) -> repository.observeMonth(m, f.type) }
            .combine(sort) { rows, s -> sorted(rows, s) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun selectMonth(month: YearMonth) {
        savedState[KEY_MONTH] = month.toString()
    }

    fun selectFilter(filter: Filter) {
        savedState[KEY_TYPE] = filter.type?.name ?: Transactions.ALL
    }

    fun selectSort(sort: Sort) {
        savedState[KEY_SORT_FIELD] = sort.field.name
        savedState[KEY_SORT_ASC] = sort.ascending
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            _deleteError.value = try {
                repository.delete(id)
                null
            } catch (e: Exception) {
                "The transaction could not be deleted."
            }
        }
    }

    fun dismissDeleteError() {
        _deleteError.value = null
    }

    fun retry() = retryTrigger.retry()

    companion object {
        const val KEY_MONTH = "month"
        const val KEY_TYPE = "type"
        const val KEY_SORT_FIELD = "sortField"
        const val KEY_SORT_ASC = "sortAscending"

        private val collator: Collator = Collator.getInstance(Locale.US)

        /**
         * Sorts without changing which rows are shown. Ties keep the ledger's order (newest
         * first by date, then by creation). Amounts compare exact minor units.
         */
        fun sorted(rows: List<Transaction>, sort: Sort): List<Transaction> {
            val comparator: Comparator<Transaction> = when (sort.field) {
                SortField.DATE -> compareBy<Transaction> { it.date }.thenBy { it.createdAt }.thenBy { it.id }
                SortField.CATEGORY -> Comparator { a, b -> collator.compare(a.categoryName, b.categoryName) }
                SortField.NOTE -> Comparator { a, b -> collator.compare(a.note.orEmpty(), b.note.orEmpty()) }
                SortField.TYPE -> compareBy { it.type.name }
                SortField.AMOUNT -> compareBy { it.amountMinor }
            }
            return rows.sortedWith(if (sort.ascending) comparator else comparator.reversed())
        }
    }
}
