package com.bookingapp.domain.importing

import com.bookingapp.domain.Category
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.importing.ImportRowParser.ParsedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Ports reference/backend/ImportRowParserTest.java, plus the android-ledger CSV scenarios. */
class ImportRowParserTest {

    private val categories = listOf(
        Category(1, "HO - Household Supplies"), Category(2, "FO - Groceries"),
        Category(3, "EN - Lottery"), Category(4, "IN - Sa"), Category(5, "TR - Gas"),
    )

    private fun parse(csv: String): List<ParsedRow> = ImportRowParser.parse(CsvReader.read(csv), categories)

    private fun one(line: String): ParsedRow = parse(line).first()

    private fun singleError(row: ParsedRow): String = row.errors.single()

    @Test
    fun sampleRow() {
        val row = one("2026/9/4,$27.25,住-日杂")

        assertTrue(row.valid)
        assertEquals(1, row.line)
        assertEquals(LocalDate.of(2026, 9, 4), row.date)
        assertEquals(2725L, row.amountMinor)
        assertEquals("住-日杂", row.sourceCategory)
        assertEquals("HO - Household Supplies", row.category?.name)
        assertEquals(TransactionType.COST, row.type)
        assertNull(row.note)
    }

    @Test
    fun isoDateAndNote() {
        val row = one("2026-09-05\t124.01\t食-食材\t  Costco  ")

        assertEquals(LocalDate.of(2026, 9, 5), row.date)
        assertEquals("Costco", row.note)
    }

    @Test
    fun usMonthDayYearDate() {
        assertEquals(LocalDate.of(2026, 2, 23), one("2/23/2026,1,住-日杂").date)
        assertEquals(LocalDate.of(2026, 2, 3), one("02/03/2026,1,住-日杂").date)
        assertEquals(LocalDate.of(2026, 12, 31), one("12-31-2026,1,住-日杂").date)
        assertTrue(singleError(one("23/2/2026,1,住-日杂")).startsWith("date:"))
        assertTrue(singleError(one("2/30/2026,1,住-日杂")).startsWith("date:"))
    }

    @Test
    fun invalidCalendarDate() {
        val row = one("2026/2/30,1,住-日杂")

        assertFalse(row.valid)
        assertNull(row.date)
        val error = singleError(row)
        assertTrue(error.startsWith("date:") && error.contains("2026/2/30"))
    }

    @Test
    fun currencySymbolAndThousandsSeparator() {
        assertEquals(123456L, one("2026/9/4\t$1,234.56\t住-日杂").amountMinor)
        assertEquals(700L, one("2026/9/4,7,娱-彩票").amountMinor)
        assertEquals(10L, one("2026/9/4,0.1,娱-彩票").amountMinor)
    }

    @Test
    fun badAmounts() {
        singleError(one("2026/9/4,3.999,娱-彩票")).let { assertTrue(it.startsWith("amount:") && it.contains("two decimal places")) }
        singleError(one("2026/9/4,0,娱-彩票")).let { assertTrue(it.startsWith("amount:") && it.contains("greater than 0")) }
        assertTrue(singleError(one("2026/9/4,-5,娱-彩票")).startsWith("amount:"))
        assertTrue(singleError(one("2026/9/4,abc,娱-彩票")).startsWith("amount:"))
        assertTrue(singleError(one("2026/9/4,99999999999999999,娱-彩票")).contains("too large"))
    }

    @Test
    fun incomeCategoryIsEarning() {
        assertEquals(TransactionType.EARNING, one("2026/9/30,3500,IN - Sa").type)
    }

    @Test
    fun unknownCategoryQuotedInError() {
        val row = one("2026/9/4,1,住-水电")

        assertNull(row.category)
        assertNull(row.type)
        singleError(row).let { assertTrue(it.startsWith("category:") && it.contains("住-水电")) }
    }

    @Test
    fun noteTooLong() {
        assertTrue(singleError(one("2026/9/4,1,住-日杂," + "x".repeat(501))).startsWith("note:"))
        assertTrue(one("2026/9/4,1,住-日杂," + "x".repeat(500)).valid)
    }

    @Test
    fun everyErrorCollected() {
        assertEquals(3, one("2026/13/1,0,住-水电").errors.size)
    }

    @Test
    fun tooFewFields() {
        assertTrue(one("2026/9/4,1").errors.any { it.contains("at least 3 fields") })
    }

    @Test
    fun headerSkipped() {
        val row = parse("日期\t金额\t目录\t备注\n2026-09-05\t124.01\t食-食材\tCostco").single()
        assertEquals(2, row.line)
        assertTrue(row.valid)
    }

    @Test
    fun englishHeaderSkipped() {
        assertEquals(2, parse("Date,Amount,Category\n2026/9/4,1,住-日杂").single().line)
    }

    @Test
    fun firstRowBadOnBothDateAndAmountIsStillData() {
        assertFalse(parse("2026/13/1,0,住-日杂").single().valid)
    }

    @Test
    fun badFirstDataRowIsNotTreatedAsHeader() {
        val rows = parse("2026/2/30,5.00,住-日杂\n2026/9/4,1,住-日杂")
        assertEquals(2, rows.size)
        assertFalse(rows.first().valid)
    }

    // android-ledger scenarios -------------------------------------------------------

    @Test
    fun chineseNameAndUsDate() {
        val row = parse("2/23/2026,\"$1,234.56\",行-汽油,\"Fill up, Costco\"").single()

        assertTrue(row.valid)
        assertEquals(1, row.line)
        assertEquals(LocalDate.of(2026, 2, 23), row.date)
        assertEquals(123456L, row.amountMinor)
        assertEquals("TR - Gas", row.category?.name)
        assertEquals(TransactionType.COST, row.type)
        assertEquals("Fill up, Costco", row.note)
    }

    @Test
    fun tabSeparatedWithHeader() {
        val row = parse("日期\t金额\t目录\t备注\n2026-09-30\t3500\tIN - Sa\tSeptember salary").single()

        assertEquals(2, row.line)
        assertEquals(TransactionType.EARNING, row.type)
        assertEquals(350000L, row.amountMinor)
        assertEquals("IN - Sa", row.category?.name)
    }

    @Test
    fun severalErrorsOnDifferentLines() {
        val lines = (1..8).map { n ->
            when (n) {
                3 -> "2026/2/30,1,住-日杂"
                7 -> "2026/9/7,1,住-水电"
                else -> "2026/9/$n,1,住-日杂"
            }
        }
        val rows = parse(lines.joinToString("\n"))

        assertEquals(listOf(3, 7), rows.filterNot { it.valid }.map { it.line })
        assertTrue(rows.first { it.line == 3 }.errors.single().startsWith("date:"))
        assertTrue(rows.first { it.line == 7 }.errors.single().contains("住-水电"))
    }

    @Test
    fun mistypedFirstRowIsReported() {
        val row = parse("2026/13/1,0,住-日杂").single()

        assertEquals(1, row.line)
        assertEquals(listOf("date:", "amount:"), row.errors.map { it.substringBefore(' ') })
    }
}
