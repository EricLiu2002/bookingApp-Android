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