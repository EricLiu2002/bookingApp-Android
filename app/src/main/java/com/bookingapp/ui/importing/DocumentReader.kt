package com.bookingapp.ui.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.bookingapp.data.reload.FileTooLargeException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads documents picked through the Storage Access Framework (design decision 11). */
interface DocumentReader {
    /** All bytes of [uri]; throws [FileTooLargeException] when it is over [maxBytes]. */
    fun readBytes(uri: Uri, maxBytes: Long): ByteArray

    fun open(uri: Uri): InputStream
}

class ContentResolverDocuments(private val resolver: ContentResolver) : DocumentReader {

    override fun readBytes(uri: Uri, maxBytes: Long): ByteArray {
        // Check the reported size first, then the bytes actually read, since a size may be missing.
        reportedSize(uri)?.let { if (it > maxBytes) throw FileTooLargeException(maxBytes) }
        open(uri).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw FileTooLargeException(maxBytes)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    override fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("The file could not be opened")

    private fun reportedSize(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

#####ImportScreen.kt
package com.bookingapp.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookingapp.data.reload.LedgerFileSummary
import com.bookingapp.domain.importing.ImportRow
import com.bookingapp.ui.common.LocalAppContainer
import com.bookingapp.ui.common.appViewModel
import com.bookingapp.ui.common.formatMoney
import com.bookingapp.ui.common.monthLabel
import com.bookingapp.ui.common.typeLabel
import com.bookingapp.ui.navigation.Transactions
import java.text.NumberFormat
import java.util.Locale

// MIME types offered by the CSV picker; the generic text type covers .txt files and unusual CSV types.
val CSV_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*")

@Composable
fun ImportRoute(onViewMonth: (Transactions) -> Unit, onLedgerReplaced: (String) -> Unit) {
    val importVm = appViewModel { c, _ -> ImportViewModel(c.documents, c.transactions, c.categories) }
    val replacer = LocalAppContainer.current.ledgerReplacer
    val reloadVm = replacer?.let { r -> appViewModel { c, _ -> LedgerReloadViewModel(r, c.documents) } }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(importVm::onCsvPicked)
    }
    val dbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { reloadVm?.onDatabasePicked(it) }
    }
    if (reloadVm != null) LaunchedEffect(reloadVm) { reloadVm.finished.collect(onLedgerReplaced) }

    val importState by importVm.state.collectAsStateWithLifecycle()
    ImportScreen(
        state = importState,
        onChooseCsv = { csvPicker.launch(CSV_MIME_TYPES) },
        onInclude = importVm::setIncluded,
        onImport = importVm::import,
        onViewMonth = onViewMonth,
        reload = reloadVm?.let { vm ->
            val state by vm.state.collectAsStateWithLifecycle()
            val hasBackup by vm.hasBackup.collectAsStateWithLifecycle()
            ReloadSection(state, hasBackup, { dbPicker.launch(arrayOf("*/*")) }, vm::confirmReplace, vm::cancel, vm::askRestore, vm::confirmRestore)
        },
    )
}

/** Everything the reload section needs, so the screen can be rendered in tests. */
class ReloadSection(
    val state: ReloadState,
    val hasBackup: Boolean,
    val onChoose: () -> Unit,
    val onReplace: () -> Unit,
    val onCancel: () -> Unit,
    val onAskRestore: () -> Unit,
    val onRestore: () -> Unit,
)

@Composable
fun ImportScreen(
    state: ImportUiState,
    onChooseCsv: () -> Unit,
    onInclude: (Int, Boolean) -> Unit,
    onImport: () -> Unit,
    onViewMonth: (Transactions) -> Unit,
    reload: ReloadSection?,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("import-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import transactions from a CSV file", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each line is date, amount, category and an optional note. Nothing is saved until you tap Import.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onChooseCsv, enabled = !state.reading && !state.importing) { Text("Choose CSV file") }
                if (state.reading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reading"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("import-error")) }
                state.result?.let { result ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Imported ${result.count} transactions", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { onViewMonth(Transactions.of(result.earliestMonth, null)) }) {
                                Text("View ${monthLabel(result.earliestMonth)}")
                            }
                        }
                    }
                }
            }
        }
        state.preview?.let { preview ->
            item { PreviewSummary(preview, state.importing, onImport) }
            items(preview.preview.rows, key = { it.parsed.line }) { row ->
                PreviewRow(row, included = preview.isSelected(row), onInclude = { onInclude(row.parsed.line, it) })
            }
        }
        if (reload != null) {
            item { ReloadSectionView(reload) }
        }
    }
}

@Composable
private fun PreviewSummary(preview: PreviewState, importing: Boolean, onImport: () -> Unit) {
    val p = preview.preview
    Card(Modifier.fillMaxWidth().testTag("preview-summary")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${p.validCount} valid · ${p.invalidCount} invalid · ${p.duplicateCount} possible duplicates")
            Text("Selected: ${preview.selected.size} rows · costs ${formatMoney(preview.selectedCosts)} · earnings ${formatMoney(preview.selectedEarnings)}")
            if (p.invalidCount > 0) {
                Text("Fix the invalid rows in the file and choose it again.", color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImport, enabled = preview.canImport && !importing, modifier = Modifier.testTag("import-button")) {
                    Text("Import")
                }
                if (importing) CircularProgressIndicator(Modifier.testTag("importing"))
            }
        }
    }
}

@Composable
private fun PreviewRow(row: ImportRow, included: Boolean, onInclude: (Boolean) -> Unit) {
    val parsed = row.parsed
    val colors = if (parsed.valid) CardDefaults.outlinedCardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag("preview-line-${parsed.line}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Line ${parsed.line} · ${parsed.date ?: "—"} · ${parsed.amountMinor?.let { formatMoney(it) } ?: "—"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("${parsed.sourceCategory} → ${parsed.category?.name ?: "?"}")
            Text(listOfNotNull(parsed.type?.let(::typeLabel), parsed.note).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            parsed.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (row.duplicate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Possible duplicate", Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary)
                    Text("Include", Modifier.padding(end = 8.dp))
                    Switch(
                        checked = included,
                        onCheckedChange = onInclude,
                        modifier = Modifier.semantics { contentDescription = "Include line ${parsed.line}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReloadSectionView(reload: ReloadSection) {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Replace ledger with a database file", style = MaterialTheme.typography.titleLarge)
        Text(
            "Stop the BookingApp backend before copying its bookingapp.db to the phone, then choose that file here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val busy = reload.state == ReloadState.Checking || reload.state == ReloadState.Working
        OutlinedButton(onClick = reload.onChoose, enabled = !busy) { Text("Choose database file") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reload-progress"))
        when (val s = reload.state) {
            is ReloadState.Rejected -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reload-error"))
            is ReloadState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reload-error"))
            else -> Unit
        }
        if (reload.hasBackup) {
            OutlinedButton(onClick = reload.onAskRestore, enabled = !busy) { Text("Restore previous ledger") }
        }
    }
    when (val s = reload.state) {
        is ReloadState.Confirm -> AlertDialog(
            onDismissRequest = reload.onCancel,
            title = { Text("Replace the phone ledger?") },
            text = { Text(confirmText(s.summary)) },
            confirmButton = { TextButton(onClick = reload.onReplace) { Text("Replace") } },
            dismissButton = { TextButton(onClick = reload.onCancel) { Text("Cancel") } },
        )
        ReloadState.ConfirmRestore -> AlertDialog(
            onDismissRequest = reload.onCancel,
            title = { Text("Restore previous ledger?") },
            text = { Text("The ledger that was replaced comes back, and the current ledger on this phone is discarded.") },
            confirmButton = { TextButton(onClick = reload.onRestore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = reload.onCancel) { Text("Cancel") } },
        )
        else -> Unit
    }
}

private fun confirmText(summary: LedgerFileSummary): String {
    val count = NumberFormat.getIntegerInstance(Locale.US)
    val range = if (summary.earliest != null) " from ${summary.earliest} to ${summary.latest}" else ""
    return "The file has ${count.format(summary.transactionCount)} transactions$range and " +
        "${count.format(summary.categoryCount)} categories. All transactions on this phone will be replaced by the file's."
}

######ImportViewModel.kt
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
#######LedgerReloadViewModel.kt
package com.bookingapp.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookingapp.data.reload.FileTooLargeException
import com.bookingapp.data.reload.LedgerFileSummary
import com.bookingapp.data.reload.LedgerFileValidator
import com.bookingapp.data.reload.LedgerReplacer
import com.bookingapp.data.reload.Rejection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

sealed interface ReloadState {
    data object Idle : ReloadState
    data object Checking : ReloadState
    data class Rejected(val message: String) : ReloadState
    data class Confirm(val summary: LedgerFileSummary) : ReloadState
    data object ConfirmRestore : ReloadState
    data object Working : ReloadState
    data class Failed(val message: String) : ReloadState
}

/** The "Replace ledger with a database file" section (design decision 15). */
class LedgerReloadViewModel(
    private val replacer: LedgerReplacer,
    private val documents: DocumentReader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<ReloadState>(ReloadState.Idle)
    val state: StateFlow<ReloadState> = _state.asStateFlow()

    val hasBackup: StateFlow<Boolean> = replacer.hasBackup

    private val done = Channel<String>(Channel.BUFFERED)

    /** Emitted after a replace or restore: the message to show on the Dashboard. */
    val finished: Flow<String> = done.receiveAsFlow()

    fun onDatabasePicked(uri: Uri) {
        _state.value = ReloadState.Checking
        viewModelScope.launch {
            _state.value = try {
                val result = withContext(io) {
                    replacer.copyIn(documents.open(uri))
                    replacer.check()
                }
                when (result) {
                    is LedgerFileValidator.Result.Valid -> ReloadState.Confirm(result.summary)
                    is LedgerFileValidator.Result.Rejected -> {
                        withContext(io) { replacer.discardIncoming() }
                        ReloadState.Rejected(messageFor(result.reason))
                    }
                }
            } catch (e: FileTooLargeException) {
                ReloadState.Rejected(messageFor(Rejection.TooLarge))
            } catch (e: Exception) {
                ReloadState.Rejected("The file could not be read.")
            }
        }
    }

    fun confirmReplace() {
        val confirm = _state.value as? ReloadState.Confirm ?: return
        _state.value = ReloadState.Working
        viewModelScope.launch {
            try {
                withContext(io) { replacer.replace() }
                _state.value = ReloadState.Idle
                done.send("Ledger replaced: ${NumberFormat.getIntegerInstance(Locale.US).format(confirm.summary.transactionCount)} transactions")
            } catch (e: Exception) {
                withContext(io) { replacer.discardIncoming() }
                _state.value = ReloadState.Failed("The ledger could not be replaced. Your phone data is unchanged.")
            }
        }
    }

    fun cancel() {
        _state.value = ReloadState.Idle
        viewModelScope.launch { withContext(io) { replacer.discardIncoming() } }
    }

    fun askRestore() {
        _state.value = ReloadState.ConfirmRestore
    }

    fun confirmRestore() {
        _state.value = ReloadState.Working
        viewModelScope.launch {
            try {
                withContext(io) { replacer.restorePrevious() }
                _state.value = ReloadState.Idle
                done.send("Previous ledger restored")
            } catch (e: Exception) {
                _state.value = ReloadState.Failed("The previous ledger could not be restored. Your phone data is unchanged.")
            }
        }
    }

    companion object {
        fun messageFor(reason: Rejection): String = when (reason) {
            Rejection.TooLarge -> "The file is larger than 100 MB, so it is not a BookingApp database."
            Rejection.NotSqlite -> "This file is not a BookingApp database."
            Rejection.Corrupt -> "The database file is damaged. Stop the backend and copy bookingapp.db again."
            Rejection.SchemaMismatch -> "This database's tables don't match the BookingApp ledger, so it is not a BookingApp database this app supports."
            is Rejection.NewerSchema -> "This database comes from a newer BookingApp backend (migration ${reason.version}). Update the app first."
            Rejection.BrokenCategoryLink -> "The database has transactions that refer to missing categories."
        }
    }
}
