package com.bookingapp.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookingapp.domain.ChartGroups
import com.bookingapp.domain.Money
import com.bookingapp.domain.Summary
import com.bookingapp.domain.TransactionType
import com.bookingapp.ui.common.MonthSelector
import com.bookingapp.ui.common.MoneyText
import com.bookingapp.ui.common.StateContent
import com.bookingapp.ui.common.UiState
import com.bookingapp.ui.common.appViewModel
import com.bookingapp.ui.common.formatMoney
import com.bookingapp.ui.common.monthLabel
import java.time.YearMonth

@Composable
fun DashboardRoute() {
    val vm = appViewModel { c, s -> DashboardViewModel(s, c.transactions, c.clock) }
    val month by vm.month.collectAsStateWithLifecycle()
    val chartType by vm.chartType.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    DashboardScreen(month, chartType, state, vm::selectMonth, vm::selectChartType, vm::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    month: YearMonth,
    chartType: TransactionType,
    state: UiState<DashboardData>,
    onMonthChange: (YearMonth) -> Unit,
    onChartTypeChange: (TransactionType) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonthSelector(month, onMonthChange)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(TransactionType.COST to "Costs", TransactionType.EARNING to "Earnings").forEachIndexed { i, (type, label) ->
                SegmentedButton(
                    selected = chartType == type,
                    onClick = { onChartTypeChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        StateContent(state, onRetry) { data -> Panels(data, chartType) }
    }
}

@Composable
private fun Panels(data: DashboardData, chartType: TransactionType) {
    val yearGroups = ChartGroups.groupsFor(data.yearSummary.breakdown, chartType)
    val colors = ChartGroups.colorsByGroup(yearGroups)
    val monthGroups = ChartGroups.groupsFor(data.monthSummary.breakdown, chartType)
    val noun = if (chartType == TransactionType.COST) "costs" else "earnings"
    val monthly = @Composable { modifier: Modifier ->
        Panel(monthLabel(data.month), data.monthSummary, slicesOf(monthGroups, colors), "No $noun recorded this month", "month", modifier)
    }
    val yearly = @Composable { modifier: Modifier ->
        Panel(data.month.year.toString(), data.yearSummary, slicesOf(yearGroups, colors), "No $noun recorded this year", "year", modifier)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                monthly(Modifier.weight(1f))
                yearly(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                monthly(Modifier.fillMaxWidth())
                yearly(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Panel(
    heading: String,
    summary: Summary,
    slices: List<Slice>,
    emptyMessage: String,
    tag: String,
    modifier: Modifier,
) {
    Card(modifier.semantics { testTag = "panel-$tag" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.titleLarge)
            Totals(summary)
            if (slices.isEmpty()) {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                PieChart(slices, Modifier.align(Alignment.CenterHorizontally).testTag("pie-$tag"))
                Breakdown(slices)
            }
        }
    }
}

@Composable
private fun Totals(summary: Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TotalRow("Income", summary.incomeMinor)
        TotalRow("Outcome", summary.outcomeMinor)
        TotalRow("Balance", summary.balanceMinor, bold = true)
    }
}

@Composable
private fun TotalRow(label: String, minor: Long, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        MoneyText(minor, bold = bold)
    }
}

@Composable
private fun Breakdown(slices: List<Slice>) {
    val total = slices.sumOf { it.group.value }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEach { slice ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(slice.color)
                Text(slice.group.label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                Text(formatMoney(slice.group.value), style = MaterialTheme.typography.bodyLarge)
                Text(slice.share, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            // Earnings slices are single categories; cost groups list their categories beneath.
            val nested = slice.group.categories.filter { it.name != slice.group.label }
            nested.forEach { category ->
                Row(Modifier.fillMaxWidth().padding(start = 24.dp)) {
                    Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatMoney(category.value), style = MaterialTheme.typography.bodyMedium)
                    Text(Money.formatShare(category.value, total), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Spacer(Modifier.size(12.dp).background(color, CircleShape))
}
