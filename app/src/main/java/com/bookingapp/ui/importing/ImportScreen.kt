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
