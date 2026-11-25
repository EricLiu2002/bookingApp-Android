package com.bookingapp.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.SummarySource
import com.bookingapp.domain.Summary
import com.bookingapp.domain.TransactionType
import com.bookingapp.ui.common.RetryTrigger
import com.bookingapp.ui.common.UiState
import com.bookingapp.ui.common.savedMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Year
import java.time.YearMonth

data class DashboardData(val month: YearMonth, val monthSummary: Summary, val yearSummary: Summary)

class DashboardViewModel(
    private val savedState: SavedStateHandle,
    private val summaries: SummarySource,
    clock: Clock,
) : ViewModel() {

    private val retryTrigger = RetryTrigger()

    /** The selected month; kept across rotation and process death. Defaults to the current month. */
    val month: StateFlow<YearMonth> = savedState.savedMonth(KEY_MONTH, clock, viewModelScope)

    /** The Costs/Earnings switch for both charts; defaults to Costs. */
    val chartType: StateFlow<TransactionType> = savedState.getStateFlow(KEY_CHART, TransactionType.COST.name)
        .map(TransactionType::valueOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransactionType.valueOf(savedState[KEY_CHART] ?: TransactionType.COST.name))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<DashboardData>> = retryTrigger.wrap("The dashboard could not be loaded.") {
        month.flatMapLatest { m ->
            combine(summaries.observeSummary(m), summaries.observeSummary(Year.of(m.year))) { monthly, yearly ->
                DashboardData(m, monthly, yearly)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun selectMonth(month: YearMonth) {
        savedState[KEY_MONTH] = month.toString()
    }

    fun selectChartType(type: TransactionType) {
        savedState[KEY_CHART] = type.name
    }

    fun retry() = retryTrigger.retry()

    companion object {
        /** Same name as the route argument, so `Dashboard(month)` pre-selects it. */
        const val KEY_MONTH = "month"
        const val KEY_CHART = "chartType"
    }
}