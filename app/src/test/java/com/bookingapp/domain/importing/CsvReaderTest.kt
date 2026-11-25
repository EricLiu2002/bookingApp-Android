package com.bookingapp.domain.importing

import com.bookingapp.domain.importing.CsvReader.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports every case of reference/backend/CsvReaderTest.java. */
class CsvReaderTest {

    @Test
    fun commaSeparated() {
        assertEquals(
            listOf(Line(1, listOf("2026/9/4", "$27.25", "住-日杂")), Line(2, listOf("2026/9/5", "124.01", "食-食材"))),
            CsvReader.read("2026/9/4,$27.25,住-日杂\n2026/9/5,124.01,食-食材"),
        )
    }

    @Test
    fun tabSeparatedKeepsCommasInsideFields() {
        assertEquals(
            listOf("2026/9/4", "$1,234.56", "住-日杂", "A, B"),
            CsvReader.read("2026/9/4\t$1,234.56\t住-日杂\tA, B").first().fields,
        )
    }

    @Test
    fun quotedFieldMayContainSeparatorAndEscapedQuote() {
        assertEquals(
            listOf("2026/9/4", "7.00", "娱-彩票", "Powerball, \"Mega\""),
            CsvReader.read("2026/9/4,7.00,娱-彩票,\"Powerball, \"\"Mega\"\"\"").first().fields,
        )
    }

    @Test
    fun blankLinesSkippedButLineNumbersKept() {
        assertEquals(listOf(2, 5), CsvReader.read("\n2026/9/4,1,a\n   \n\n2026/9/5,2,b\n").map { it.number })
    }

    @Test
    fun leadingBomIgnored() {
        assertEquals("2026/9/4", CsvReader.read(CsvReader.BOM + "2026/9/4,1,a").first().fields.first())
    }

    @Test
    fun crlfAndCrLineEndings() {
        assertEquals(listOf(1, 2, 3), CsvReader.read("a,1,x\r\nb,2,y\rc,3,z").map { it.number })
    }

    @Test
    fun separatorDetectedFromFirstNonBlankLineOnly() {
        // First line has no tab, so the file is comma-separated even if a later note has a tab.
        assertEquals(listOf("b", "2", "y", "note\twith tab"), CsvReader.read("a,1,x\nb,2,y,note\twith tab")[1].fields)
    }

    @Test
    fun emptyInputHasNoLines() {
        assertTrue(CsvReader.read("").isEmpty())
        assertTrue(CsvReader.read("\n \r\n").isEmpty())
    }
}
