package com.bookingapp.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.CategoryRepository
import com.bookingapp.data.TransactionRepository
import com.bookingapp.data.reload.FileTooLargeException
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.importing.CsvDecoder
import com.bookingapp.domain.importing.ImportFileException
import com.bookingapp.domain.importing.ImportPreview
import com.bookingapp.domain.importing.ImportPreviewBuilder
import com.bookingapp.domain.importing.ImportRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** A preview plus which possible duplicates the user chose to include (by line). */
data class PreviewState(val preview: ImportPreview, val includedDuplicates: Set<Int> = emptySet()) {

    fun isSelected(row: ImportRow): Boolean = row.parsed.valid && (!row.duplicate || row.parsed.line in includedDuplicates)

    val selected: List<ImportRow> get() = preview.rows.filter(::isSelected)

    val selectedCosts: Long get() = selected.filter { it.parsed.type == TransactionType.COST }.sumOf { it.parsed.amountMinor!! }

    val selectedEarnings: Long get() = selected.filter { it.parsed.type == TransactionType.EARNING }.sumOf { it.parsed.amountMinor!! }

    /** Import is allowed only with no invalid rows and at least one selected row. */
    val canImport: Boolean get() = preview.invalidCount == 0 && selected.isNotEmpty()
}

data class ImportResult(val count: Int, val earliestMonth: YearMonth)

data class ImportUiState(
    val reading: Boolean = false,
    val importing: Boolean = false,
    val preview: PreviewState? = null,
    val error: String? = null,
    val result: ImportResult? = null,
)

class ImportViewModel(
    private val documents: DocumentReader,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Reads, decodes and previews a picked file. Choosing another file replaces the preview. */
    fun onCsvPicked(uri: Uri) {
        job?.cancel()
        _state.value = ImportUiState(reading = true)
        job = viewModelScope.launch {
            _state.value = try {
                val preview = withContext(work) {
                    val bytes = documents.readBytes(uri, CsvDecoder.MAX_IMPORT_BYTES)
                    val text = CsvDecoder.decode(bytes).text
                    val cats = categories.getAll()
                    ImportPreviewBuilder.build(text, cats, transactions::countMatching)
                }
                ImportUiState(preview = PreviewState(preview))
            } catch (e: FileTooLargeException) {
                ImportUiState(error = CsvDecoder.TOO_LARGE_MESSAGE)
            } catch (e: ImportFileException) {
                ImportUiState(error = e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportUiState(error = "The file could not be read.")
            }
        }
    }

    fun setIncluded(line: Int, included: Boolean) {
        val current = _state.value.preview ?: return
        val lines = if (included) current.includedDuplicates + line else current.includedDuplicates - line
        _state.value = _state.value.copy(preview = current.copy(includedDuplicates = lines))
    }

    /** Saves every selected row in one transaction; on failure the preview is kept. */
    fun import() {
        val preview = _state.value.preview ?: return
        if (!preview.canImport || _state.value.importing) return
        val inputs = preview.selected.map { it.toInput()!! }
        _state.value = _state.value.copy(importing = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                val count = transactions.importAll(inputs)
                ImportUiState(result = ImportResult(count, YearMonth.from(inputs.minOf { it.date })))
            } catch (e: Exception) {
                _state.value.copy(importing = false, error = "The import failed and nothing was saved. ${e.message.orEmpty()}".trim())
            }
        }
    }
}
