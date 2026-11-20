package com.bookingapp.domain.importing

import com.bookingapp.domain.Category
import com.bookingapp.domain.Money
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.TransactionValidator
import com.bookingapp.domain.jstrip
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Port of the backend's `ImportRowParser`: turns CSV lines (`date, amount, category[, note]`)
 * into rows, collecting every problem per row instead of stopping at the first one.
 */
object ImportRowParser {

    const val MAX_NOTE_LENGTH = TransactionValidator.MAX_NOTE_LENGTH
    private const val INCOME_PREFIX = "IN - "

    /** Year first: `2026/9/4` or `2026-09-04`. */
    private val DATE_YMD = Regex("^(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})$")

    /** US month/day/year: `2/23/2026` or `02-23-2026`. */
    private val DATE_MDY = Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$")
    private val AMOUNT = Regex("^\\d+(\\.\\d{1,2})?$")
    private val TOO_MANY_DECIMALS = Regex("^\\d+\\.\\d{3,}$")
    private val DIGIT = Regex("\\d")

    /** One data row; fields that could not be parsed are null and explained in [errors]. */
    data class ParsedRow(
        val line: Int,
        val date: LocalDate?,
        val amountMinor: Long?,
        val sourceCategory: String,
        val category: Category?,
        val type: TransactionType?,
        val note: String?,
        val errors: List<String>,
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    /** Parses all lines, skipping the first one when it is a header. */
    fun parse(lines: List<CsvReader.Line>, categories: Collection<Category>): List<ParsedRow> =
        lines.filterIndexed { i, line -> !(i == 0 && isHeader(line.fields)) }
            .map { parseRow(it, categories) }

    /**
     * A header is a first line whose date and amount fields contain no digits at all
     * (e.g. `日期,金额` or `Date,Amount`). A data row with typos still has digits, so it is
     * reported as invalid rather than silently skipped.
     */
    internal fun isHeader(fields: List<String>): Boolean =
        !DIGIT.containsMatchIn(field(fields, 0)) && !DIGIT.containsMatchIn(field(fields, 1))

    internal fun parseRow(line: CsvReader.Line, categories: Collection<Category>): ParsedRow {
        val fields = line.fields
        val errors = ArrayList<String>()
        if (fields.size < 3) {
            errors += "expected at least 3 fields (date, amount, category) but found ${fields.size}"
        }

        val rawDate = field(fields, 0)
        val date = parseDate(rawDate)
        if (date == null && fields.isNotEmpty()) {
            errors += "date: \"$rawDate\" is not a valid date (use e.g. 2/23/2026, 2026/2/23 or 2026-02-23)"
        }

        val amount = parseAmount(field(fields, 1))
        if (amount.error != null && fields.size >= 2) {
            errors += "amount: ${amount.error}"
        }

        val sourceCategory = field(fields, 2)
        var category: Category? = null
        var type: TransactionType? = null
        if (fields.size >= 3) {
            category = CategoryAliases.resolve(sourceCategory, categories)
            if (category == null) {
                errors += "category: \"$sourceCategory\" is not a known category"
            } else {
                type = if (category.name.startsWith(INCOME_PREFIX)) TransactionType.EARNING else TransactionType.COST
            }
        }

        var note: String? = field(fields, 3)
        if (note!!.isEmpty()) {
            note = null
        } else if (note.length > MAX_NOTE_LENGTH) {
            errors += "note: longer than $MAX_NOTE_LENGTH characters"
        }

        return ParsedRow(line.number, date, amount.minor, sourceCategory, category, type, note, errors.toList())
    }

    internal fun parseDate(text: String): LocalDate? = try {
        DATE_YMD.matchEntire(text)?.let { m ->
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        } ?: DATE_MDY.matchEntire(text)?.let { m ->
            LocalDate.of(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    } catch (e: DateTimeException) {
        null
    }

    internal data class AmountResult(val minor: Long?, val error: String?)

    internal fun parseAmount(text: String): AmountResult {
        var cleaned = if (text.startsWith("$")) text.substring(1) else text
        cleaned = cleaned.replace(",", "").jstrip()
        if (!AMOUNT.matches(cleaned)) {
            val reason = if (TOO_MANY_DECIMALS.matches(cleaned)) "use at most two decimal places"
            else "must be a positive number, e.g. 27.25 or \$1,234.56"
            return AmountResult(null, "\"$text\" $reason")
        }
        val minor = BigDecimal(cleaned).movePointRight(2)
        if (minor.signum() <= 0) return AmountResult(null, "\"$text\" must be greater than 0")
        if (minor > BigDecimal.valueOf(Money.MAX_AMOUNT_MINOR)) return AmountResult(null, "\"$text\" is too large")
        return AmountResult(minor.longValueExact(), null)
    }

    private fun field(fields: List<String>, index: Int): String =
        if (index < fields.size) fields[index].jstrip() else ""
}
