package com.bookingapp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bookingapp.ui.dashboard.DashboardRoute
import com.bookingapp.ui.edit.EditRoute
import com.bookingapp.ui.importing.ImportRoute
import com.bookingapp.ui.transactions.TransactionsRoute
import kotlinx.coroutines.launch

private enum class TopLevel(val label: String, val icon: ImageVector, val route: Any) {
    DASHBOARD("Dashboard", Icons.Filled.Home, Dashboard()),
    TRANSACTIONS("Transactions", Icons.AutoMirrored.Filled.List, Transactions()),
    ADD("Add", Icons.Filled.Add, Edit()),
    IMPORT("Import", Icons.AutoMirrored.Filled.ExitToApp, Import),
}

/** Navigates to a top-level screen, keeping only the Dashboard beneath it. */
private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    popUpTo<Dashboard>()
    launchSingleTop = true
}

/** Opens the Dashboard with nothing from before left on the back stack. */
private fun NavHostController.restartAtDashboard() = navigate(Dashboard()) {
    popUpTo(graph.id) { inclusive = true }
}

@Composable
fun BookingNavHost(navController: NavHostController = rememberNavController()) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { item ->
                    val selected = when (item) {
                        TopLevel.DASHBOARD -> destination?.hasRoute<Dashboard>() == true
                        TopLevel.TRANSACTIONS -> destination?.hasRoute<Transactions>() == true ||
                            (destination?.hasRoute<Edit>() == true && entry?.toRoute<Edit>()?.id != null)
                        TopLevel.ADD -> destination?.hasRoute<Edit>() == true && entry?.toRoute<Edit>()?.id == null
                        TopLevel.IMPORT -> destination?.hasRoute<Import>() == true
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateTopLevel(item.route) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Dashboard(), modifier = Modifier.padding(padding)) {
            composable<Dashboard> { DashboardRoute() }
            composable<Transactions> {
                TransactionsRoute(
                    onEdit = { id -> navController.navigate(Edit(id)) },
                    onAdd = { navController.navigateTopLevel(Edit()) },
                )
            }
            composable<Edit> {
                EditRoute(
                    onViewInList = { route -> navController.navigateTopLevel(route) },
                    onUpdated = { route -> navController.navigateTopLevel(route) },
                )
            }
            composable<Import> {
                ImportRoute(
                    onViewMonth = { route -> navController.navigateTopLevel(route) },
                    onLedgerReplaced = { message ->
                        navController.restartAtDashboard()
                        scope.launch { snackbar.showSnackbar(message) }
                    },
                )
            }
        }
    }
}

###Routes.kt
package com.bookingapp.ui.navigation

import com.bookingapp.domain.TransactionType
import kotlinx.serialization.Serializable
import java.time.YearMonth

/** Typed routes (design decision 8). Months are `YYYY-MM` text; null means the current month. */
@Serializable
data class Dashboard(val month: String? = null)

/** @param type `COST`, `EARNING` or `ALL`; null means the default filter (Costs). */
@Serializable
data class Transactions(val month: String? = null, val type: String? = null) {
    companion object {
        const val ALL = "ALL"

        fun of(month: YearMonth, type: TransactionType?) = Transactions(month.toString(), type?.name ?: ALL)
    }
}

/** Add when [id] is null, otherwise edit that transaction. */
@Serializable
data class Edit(val id: Long? = null)

@Serializable
data object Import


####theme/Theme.kt
package com.bookingapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Ledger-specific colours on top of Material 3. */
@Immutable
data class LedgerColors(val income: Color, val cost: Color, val negative: Color)

private val LightLedger = LedgerColors(income = Color(0xFF15803D), cost = Color(0xFF1D4ED8), negative = Color(0xFFB91C1C))
private val DarkLedger = LedgerColors(income = Color(0xFF4ADE80), cost = Color(0xFF93C5FD), negative = Color(0xFFF87171))

val LocalLedgerColors = staticCompositionLocalOf { LightLedger }

private val LightScheme = lightColorScheme(primary = Color(0xFF2563EB), secondary = Color(0xFF0891B2))
private val DarkScheme = darkColorScheme(primary = Color(0xFF93C5FD), secondary = Color(0xFF67E8F9))

@Composable
fun BookingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLedgerColors provides if (darkTheme) DarkLedger else LightLedger) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
    }
}

object LedgerTheme {
    val colors: LedgerColors
        @Composable get() = LocalLedgerColors.current
}


###transactions/TransactionsScreen.kt
package com.bookingapp.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookingapp.domain.Transaction
import com.bookingapp.ui.common.EmptyState
import com.bookingapp.ui.common.MonthSelector
import com.bookingapp.ui.common.MoneyText
import com.bookingapp.ui.common.StateContent
import com.bookingapp.ui.common.UiState
import com.bookingapp.ui.common.appViewModel
import com.bookingapp.ui.common.formatMoney
import com.bookingapp.ui.common.typeLabel
import com.bookingapp.ui.theme.LedgerTheme
import java.time.YearMonth

@Composable
fun TransactionsRoute(onEdit: (Long) -> Unit, onAdd: () -> Unit) {
    val vm = appViewModel { c, s -> TransactionsViewModel(s, c.transactions, c.clock) }
    val month by vm.month.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    TransactionsScreen(
        month, filter, sort, state, deleteError,
        onMonthChange = vm::selectMonth, onFilterChange = vm::selectFilter, onSortChange = vm::selectSort,
        onEdit = onEdit, onDelete = vm::delete, onDismissError = vm::dismissDeleteError, onAdd = onAdd, onRetry = vm::retry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    month: YearMonth,
    filter: Filter,
    sort: Sort,
    state: UiState<List<Transaction>>,
    deleteError: String?,
    onMonthChange: (YearMonth) -> Unit,
    onFilterChange: (Filter) -> Unit,
    onSortChange: (Sort) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismissError: () -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        MonthSelector(month, onMonthChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                Filter.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = filter == f,
                        onClick = { onFilterChange(f) },
                        shape = SegmentedButtonDefaults.itemShape(i, Filter.entries.size),
                    ) { Text(f.label) }
                }
            }
            SortMenu(sort, onSortChange)
        }
        if (deleteError != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deleteError, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }
        StateContent(state, onRetry) { rows ->
            if (rows.isEmpty()) {
                EmptyState("No transactions this month", actionLabel = "Add", onAction = onAdd)
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("transaction-list")) {
                    items(rows, key = { it.id }) { row ->
                        TransactionRow(row, onClick = { onEdit(row.id) }, onDelete = { pendingDelete = row })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete transaction?") },
            text = { Text("Delete this ${typeLabel(row.type).lowercase()} of ${formatMoney(row.amountMinor)}?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(row.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SortMenu(sort: Sort, onSortChange: (Sort) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("sort-button")) {
            Text("${sort.field.label} ${if (sort.ascending) "↑" else "↓"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { field ->
                listOf(true, false).forEach { ascending ->
                    val label = "${field.label}, ${if (ascending) "ascending" else "descending"}"
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSortChange(Sort(field, ascending))
                        },
                        trailingIcon = if (sort == Sort(field, ascending)) ({ Text("✓") }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(row: Transaction, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag("row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.categoryName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${row.date} · ${typeLabel(row.type)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (row.type == com.bookingapp.domain.TransactionType.EARNING) LedgerTheme.colors.income else LedgerTheme.colors.cost,
            )
            row.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        MoneyText(row.amountMinor, type = row.type, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("amount"))
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}

###transactions/TransactionsViewModel.kt
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
