package com.bookingapp.domain.importing

import com.bookingapp.domain.TransactionInput
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.importing.ImportRowParser.ParsedRow
import java.time.LocalDate

/** The file cannot be previewed at all (no rows, too many rows, unreadable). */
class ImportFileException(message: String) : Exception(message)

/** The ledger-side identity of a possible duplicate. Notes are ignored. */
data class DuplicateKey(val date: LocalDate, val amountMinor: Long, val categoryId: Long, val type: TransactionType)

data class ImportRow(val parsed: ParsedRow, val duplicate: Boolean) {
    /** The input to save, for a valid row. */
    fun toInput(): TransactionInput? = if (!parsed.valid) null else TransactionInput(
        type = parsed.type!!,
        amountMinor = parsed.amountMinor!!,
        date = parsed.date!!,
        categoryId = parsed.category!!.id,
        note = parsed.note,
    )
}

data class ImportPreview(
    val rows: List<ImportRow>,
    val validCount: Int,
    val invalidCount: Int,
    val duplicateCount: Int,
)

/**
 * Flags possible duplicates (the backend's `ImportService.preview`): for each key, only as
 * many valid rows as the ledger has matching transactions, starting with the earliest line.
 */
object DuplicateFlagger {
    fun flag(rows: List<ParsedRow>, ledgerCounts: Map<DuplicateKey, Int>): List<Boolean> {
        val remaining = ledgerCounts.toMutableMap()
        return rows.sortedBy { it.line }.associate { row ->
            val duplicate = if (row.valid) {
                val key = DuplicateKey(row.date!!, row.amountMinor!!, row.category!!.id, row.type!!)
                val left = remaining[key] ?: 0
                if (left > 0) remaining[key] = left - 1
                left > 0
            } else {
                false
            }
            row.line to duplicate
        }.let { byLine -> rows.map { byLine.getValue(it.line) } }
    }
}

/** Parses, maps and checks CSV text and flags possible duplicates. Saves nothing. */
object ImportPreviewBuilder {

    const val MAX_ROWS = 5000

    /**
     * @param ledgerCounts ledger transactions per key dated between the two dates, inclusive.
     */
    fun build(
        text: String,
        categories: Collection<com.bookingapp.domain.Category>,
        ledgerCounts: (first: LocalDate, last: LocalDate) -> Map<DuplicateKey, Int>,
    ): ImportPreview {
        val parsed = ImportRowParser.parse(CsvReader.read(text), categories)
        if (parsed.isEmpty()) throw ImportFileException("The file has no rows to import")
        if (parsed.size > MAX_ROWS) {
            throw ImportFileException("The file has ${parsed.size} rows; at most $MAX_ROWS can be imported at once")
        }
        val dates = parsed.filter { it.valid }.map { it.date!! }
        val counts = if (dates.isEmpty()) emptyMap() else ledgerCounts(dates.min(), dates.max())
        val flags = DuplicateFlagger.flag(parsed, counts)
        val rows = parsed.zip(flags) { row, duplicate -> ImportRow(row, duplicate) }
        val valid = parsed.count { it.valid }
        return ImportPreview(rows, valid, parsed.size - valid, flags.count { it })
    }
}
