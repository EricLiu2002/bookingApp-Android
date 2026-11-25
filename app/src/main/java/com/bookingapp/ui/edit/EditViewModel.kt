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
