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

####################################### DashboardViewModel.kt
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
####################################### PieChart.kt
package com.bookingapp.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookingapp.domain.ChartGroups
import com.bookingapp.domain.Money
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One slice of a pie: its group, colour and whether it gets a label (5% or more). */
data class Slice(val group: ChartGroups.Group, val color: Color, val share: String, val labelled: Boolean)

fun slicesOf(groups: List<ChartGroups.Group>, colors: Map<String, Long>): List<Slice> {
    val total = groups.sumOf { it.value }
    return groups.mapIndexed { index, group ->
        val argb = colors[group.key] ?: ChartGroups.SLICE_COLORS[index % ChartGroups.SLICE_COLORS.size]
        Slice(group, Color(argb), Money.formatShare(group.value, total), !ChartGroups.isSmallShare(group.value, total))
    }
}

/**
 * A pie drawn with Canvas arcs. Only slices of 5% or more get a label; the labels are also
 * exposed as the chart's content description.
 */
@Composable
fun PieChart(slices: List<Slice>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val outline = MaterialTheme.colorScheme.surface
    val total = slices.sumOf { it.group.value }.toDouble()
    val description = slices.filter { it.labelled }.joinToString(", ") { "${it.group.label} ${it.share}" }
    Canvas(
        modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = "Pie chart: $description" },
    ) {
        if (total <= 0) return@Canvas
        val diameter = min(size.width, size.height)
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val center = Offset(size.width / 2, size.height / 2)
        var start = -90f
        slices.forEach { slice ->
            // Display only: the angle is derived from exact totals and never fed back into sums.
            val sweep = (slice.group.value / total * 360.0).toFloat()
            drawArc(slice.color, start, sweep, useCenter = true, topLeft = topLeft, size = Size(diameter, diameter))
            if (slices.size > 1) {
                drawArc(outline, start, sweep, useCenter = true, topLeft = topLeft, size = Size(diameter, diameter), style = Stroke(2f))
            }
            if (slice.labelled) {
                val mid = Math.toRadians((start + sweep / 2).toDouble())
                val radius = diameter / 2 * if (slices.size == 1) 0f else 0.62f
                val text = measurer.measure("${slice.group.label}\n${slice.share}", labelStyle)
                val at = Offset(
                    center.x + (radius * cos(mid)).toFloat() - text.size.width / 2,
                    center.y + (radius * sin(mid)).toFloat() - text.size.height / 2,
                )
                drawText(text, topLeft = at)
            }
            start += sweep
        }
    }
}
