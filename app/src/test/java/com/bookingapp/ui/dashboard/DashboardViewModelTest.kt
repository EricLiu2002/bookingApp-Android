package com.bookingapp.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bookingapp.data.SummarySource
import com.bookingapp.domain.Summary
import com.bookingapp.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)

    /** Fails until [healthy] is set; counts how often it was asked. */
    private class FlakySource : SummarySource {
        var healthy = false
        var calls = 0
        override fun observeSummary(month: YearMonth): Flow<Summary> = source(Summary(0, 5000, emptyList()))
        override fun observeSummary(year: Year): Flow<Summary> = source(Summary(0, 32000, emptyList()))
        private fun source(value: Summary) = flow {
            calls++
            if (!healthy) throw IllegalStateException("disk I/O error")
            emit(value)
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failingRepositoryShowsErrorAndRetryReloads() = runTest {
        val source = FlakySource()
        val vm = DashboardViewModel(SavedStateHandle(), source, clock)

        vm.state.test {
            var item = awaitItem()
            while (item is UiState.Loading) item = awaitItem()
            assertEquals(UiState.Error("The dashboard could not be loaded."), item)

            source.healthy = true
            vm.retry()

            item = awaitItem()
            while (item is UiState.Loading) item = awaitItem()
            val data = (item as UiState.Content).data
            assertEquals(5000L, data.monthSummary.outcomeMinor)
            assertEquals(32000L, data.yearSummary.outcomeMinor)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(source.calls >= 2)
    }

    @Test
    fun defaultsToCurrentMonthAndRestoresSavedMonth() {
        val source = object : SummarySource {
            override fun observeSummary(month: YearMonth) = flowOf(Summary.EMPTY)
            override fun observeSummary(year: Year) = flowOf(Summary.EMPTY)
        }
        assertEquals(YearMonth.of(2026, 10), DashboardViewModel(SavedStateHandle(), source, clock).month.value)

        val saved = SavedStateHandle(mapOf("month" to "2025-03"))
        assertEquals(YearMonth.of(2025, 3), DashboardViewModel(saved, source, clock).month.value)
    }

    @Test
    fun steppingIntoAnotherYearLoadsThatYear() = runTest {
        val years = mutableListOf<Year>()
        val source = object : SummarySource {
            override fun observeSummary(month: YearMonth) = flowOf(Summary.EMPTY)
            override fun observeSummary(year: Year) = flowOf(Summary.EMPTY).also { years += year }
        }
        val vm = DashboardViewModel(SavedStateHandle(mapOf("month" to "2026-01")), source, clock)
        vm.state.test {
            vm.selectMonth(vm.month.value.minusMonths(1))
            var item = awaitItem()
            while (item !is UiState.Content || item.data.month != YearMonth.of(2025, 12)) item = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(Year.of(2025), years.last())
    }
}
