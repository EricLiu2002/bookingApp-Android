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

#############EditViewModel.kt
package com.bookingapp.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.CategoryRepository
import com.bookingapp.data.TransactionRepository
import com.bookingapp.domain.Category
import com.bookingapp.domain.Field
import com.bookingapp.domain.Money
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.TransactionValidator
import com.bookingapp.domain.ValidationException
import com.bookingapp.ui.navigation.Transactions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/** The form's typed input; kept in SavedStateHandle so it survives rotation. */
data class EditForm(
    val type: TransactionType = TransactionType.COST,
    val amount: String = "",
    val date: LocalDate,
    val categoryId: Long? = null,
    val note: String = "",
)

/** The confirmation shown after a new transaction is saved. */
data class SavedConfirmation(val amountMinor: Long, val categoryName: String, val date: LocalDate, val type: TransactionType) {
    val listRoute: Transactions get() = Transactions.of(YearMonth.from(date), type)
}

data class EditUiState(
    val isEdit: Boolean,
    val loading: Boolean,
    val form: EditForm,
    val categories: List<Category>,
    val errors: Map<Field, String> = emptyMap(),
    val generalError: String? = null,
    val saving: Boolean = false,
    val confirmation: SavedConfirmation? = null,
    /** Increments each time the amount field should take focus. */
    val focusAmount: Int = 0,
)

class EditViewModel(
    private val savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    categories: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    /** The transaction being edited, or null when adding. Same key as the `Edit` route argument. */
    private val id: Long? = savedState.get<Long>(KEY_ID)

    private val form = MutableStateFlow(restoreForm())
    private val transient = MutableStateFlow(Transient(loading = id != null && savedState.get<Boolean>(KEY_LOADED) != true))

    private data class Transient(
        val loading: Boolean = false,
        val errors: Map<Field, String> = emptyMap(),
        val generalError: String? = null,
        val saving: Boolean = false,
        val confirmation: SavedConfirmation? = null,
        val focusAmount: Int = 0,
    )

    private val navigation = Channel<Transactions>(Channel.BUFFERED)

    /** Emitted after a successful update: the Monthly List to return to. */
    val navigateToList: Flow<Transactions> = navigation.receiveAsFlow()

    private val categoryList: StateFlow<List<Category>> =
        categories.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<EditUiState> = combine(form, transient, categoryList) { f, t, cats ->
        EditUiState(
            isEdit = id != null, loading = t.loading, form = f, categories = cats, errors = t.errors,
            generalError = t.generalError, saving = t.saving, confirmation = t.confirmation, focusAmount = t.focusAmount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditUiState(id != null, transient.value.loading, form.value, emptyList()))

    init {
        if (id != null && savedState.get<Boolean>(KEY_LOADED) != true) {
            viewModelScope.launch {
                val existing = try {
                    transactions.get(id)
                } catch (e: Exception) {
                    null
                }
                if (existing == null) {
                    transient.value = transient.value.copy(loading = false, generalError = "This transaction no longer exists.")
                } else {
                    updateForm(
                        EditForm(existing.type, Money.minorToMajorInput(existing.amountMinor), existing.date, existing.categoryId, existing.note.orEmpty()),
                        clearConfirmation = false,
                    )
                    savedState[KEY_LOADED] = true
                    transient.value = transient.value.copy(loading = false)
                }
            }
        }
    }

    fun setType(type: TransactionType) = edit { it.copy(type = type) }
    fun setAmount(text: String) = edit { it.copy(amount = text) }
    fun setDate(date: LocalDate) = edit { it.copy(date = date) }
    fun setCategory(id: Long) = edit { it.copy(categoryId = id) }
    fun setNote(text: String) = edit { it.copy(note = text) }

    /** Any edit hides the last confirmation. */
    private fun edit(change: (EditForm) -> EditForm) = updateForm(change(form.value), clearConfirmation = true)

    private fun updateForm(next: EditForm, clearConfirmation: Boolean) {
        form.value = next
        savedState[KEY_TYPE] = next.type.name
        savedState[KEY_AMOUNT] = next.amount
        savedState[KEY_DATE] = next.date.toString()
        savedState[KEY_CATEGORY] = next.categoryId
        savedState[KEY_NOTE] = next.note
        if (clearConfirmation && transient.value.confirmation != null) {
            transient.value = transient.value.copy(confirmation = null)
        }
    }

    fun save() {
        if (transient.value.saving) return
        val f = form.value
        val cats = categoryList.value
        val result = TransactionValidator.validateForm(f.type, f.amount, f.date, f.categoryId, f.note) { id ->
            // Existence is checked again by the repository inside its transaction.
            cats.isEmpty() || cats.any { it.id == id }
        }
        if (result is TransactionValidator.Result.Invalid) {
            transient.value = transient.value.copy(errors = result.errors, generalError = null, confirmation = null)
            return
        }
        val input = (result as TransactionValidator.Result.Valid).input
        transient.value = transient.value.copy(saving = true, errors = emptyMap(), generalError = null, confirmation = null)
        viewModelScope.launch {
            try {
                if (id == null) {
                    transactions.create(input)
                    val name = cats.firstOrNull { it.id == input.categoryId }?.name.orEmpty()
                    // Ready for the next entry: keep date and type, clear the rest.
                    updateForm(f.copy(amount = "", categoryId = null, note = ""), clearConfirmation = false)
                    transient.value = transient.value.copy(
                        saving = false,
                        confirmation = SavedConfirmation(input.amountMinor, name, input.date, input.type),
                        focusAmount = transient.value.focusAmount + 1,
                    )
                } else {
                    transactions.update(id, input)
                    transient.value = transient.value.copy(saving = false)
                    navigation.send(Transactions.of(YearMonth.from(input.date), input.type))
                }
            } catch (e: ValidationException) {
                transient.value = transient.value.copy(saving = false, errors = e.errors)
            } catch (e: Exception) {
                transient.value = transient.value.copy(saving = false, generalError = "The transaction could not be saved. Try again.")
            }
        }
    }

    private fun restoreForm() = EditForm(
        type = savedState.get<String>(KEY_TYPE)?.let(TransactionType::valueOf) ?: TransactionType.COST,
        amount = savedState[KEY_AMOUNT] ?: "",
        date = savedState.get<String>(KEY_DATE)?.let(LocalDate::parse) ?: LocalDate.now(clock),
        categoryId = savedState[KEY_CATEGORY],
        note = savedState[KEY_NOTE] ?: "",
    )

    companion object {
        const val KEY_ID = "id"
        private const val KEY_LOADED = "form.loaded"
        private const val KEY_TYPE = "form.type"
        private const val KEY_AMOUNT = "form.amount"
        private const val KEY_DATE = "form.date"
        private const val KEY_CATEGORY = "form.category"
        private const val KEY_NOTE = "form.note"
    }
}
