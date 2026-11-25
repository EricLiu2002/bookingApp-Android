package com.bookingapp.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookingapp.domain.Field
import com.bookingapp.domain.TransactionType
import com.bookingapp.ui.common.LoadingState
import com.bookingapp.ui.common.appViewModel
import com.bookingapp.ui.common.formatMoney
import com.bookingapp.ui.navigation.Transactions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun EditRoute(onViewInList: (Transactions) -> Unit, onUpdated: (Transactions) -> Unit) {
    val vm = appViewModel { c, s -> EditViewModel(s, c.transactions, c.categories, c.clock) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navigateToList.collect(onUpdated) }
    EditScreen(
        state,
        EditActions(vm::setType, vm::setAmount, vm::setDate, vm::setCategory, vm::setNote, vm::save, onViewInList),
    )
}

class EditActions(
    val onType: (TransactionType) -> Unit,
    val onAmount: (String) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onCategory: (Long) -> Unit,
    val onNote: (String) -> Unit,
    val onSave: () -> Unit,
    val onViewInList: (Transactions) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(state: EditUiState, actions: EditActions) {
    if (state.loading) {
        LoadingState()
        return
    }
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(state.focusAmount) { if (state.focusAmount > 0) amountFocus.requestFocus() }
    val form = state.form

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (state.isEdit) "Edit transaction" else "Add transaction", style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(TransactionType.COST to "Cost", TransactionType.EARNING to "Earning").forEachIndexed { i, (type, label) ->
                SegmentedButton(
                    selected = form.type == type,
                    onClick = { actions.onType(type) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }

        OutlinedTextField(
            value = form.amount,
            onValueChange = actions.onAmount,
            label = { Text("Amount") },
            singleLine = true,
            isError = Field.AMOUNT in state.errors,
            supportingText = state.errors[Field.AMOUNT]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus).testTag("amount"),
        )

        DateField(form.date, state.errors[Field.DATE], actions.onDate)

        CategoryField(state, actions.onCategory)

        OutlinedTextField(
            value = form.note,
            onValueChange = actions.onNote,
            label = { Text("Note (optional)") },
            isError = Field.NOTE in state.errors,
            supportingText = state.errors[Field.NOTE]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().testTag("note"),
        )

        state.generalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = actions.onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("save")) {
            Text(if (state.isEdit) "Save changes" else "Save")
        }

        state.confirmation?.let { saved ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().testTag("confirmation"),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Saved ${formatMoney(saved.amountMinor)} · ${saved.categoryName} · ${saved.date}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { actions.onViewInList(saved.listRoute) }) { Text("View in list") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, error: String?, onDate: (LocalDate) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = date.toString(),
        onValueChange = {},
        readOnly = true,
        label = { Text("Date") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.DateRange, contentDescription = "Choose date") }
        },
        modifier = Modifier.fillMaxWidth().testTag("date"),
    )
    if (picking) {
        // The picker works in UTC milliseconds; only the calendar date is used.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(pickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(state: EditUiState, onCategory: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.categories.firstOrNull { it.id == state.form.categoryId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            isError = Field.CATEGORY in state.errors,
            supportingText = state.errors[Field.CATEGORY]?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("category"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        expanded = false
                        onCategory(category.id)
                    },
                )
            }
        }
    }
}


