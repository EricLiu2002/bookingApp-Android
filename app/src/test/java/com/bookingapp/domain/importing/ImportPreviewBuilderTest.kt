package com.bookingapp.domain.importing

import com.bookingapp.data.TransactionRepository
import com.bookingapp.domain.Category
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.TestDatabases
import com.bookingapp.testing.TickingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ImportPreviewBuilderTest {

    private val lottery = Category(26, "EN - Lottery")
    private val categories = listOf(lottery, Category(5, "FO - Groceries"))
    private val noLedger: (LocalDate, LocalDate) -> Map<DuplicateKey, Int> = { _, _ -> emptyMap() }

    @Test
    fun rejectsFileWithoutRows() {
        val error = assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build("\n\n", categories, noLedger) }
        assertEquals("The file has no rows to import", error.message)
        // A header alone has no data rows either.
        assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build("Date,Amount,Category", categories, noLedger) }
    }

    @Test
    fun rejectsMoreThan5000Rows() {
        val text = List(5001) { "2026/9/4,1,娱-彩票" }.joinToString("\n")
        val error = assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build(text, categories, noLedger) }
        assertEquals("The file has 5001 rows; at most 5000 can be imported at once", error.message)
        assertEquals(5000, ImportPreviewBuilder.build(text.substringAfter('\n'), categories, noLedger).rows.size)
    }

    @Test
    fun countsValidInvalidAndDuplicates() {
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        val preview = ImportPreviewBuilder.build("2026/9/4,7,娱-彩票\n2026/9/5,abc,娱-彩票\n2026/9/6,1,食-食材", categories) { first, last ->
            assertEquals(LocalDate.of(2026, 9, 4), first)
            assertEquals(LocalDate.of(2026, 9, 6), last)
            mapOf(key to 1)
        }

        assertEquals(2, preview.validCount)
        assertEquals(1, preview.invalidCount)
        assertEquals(1, preview.duplicateCount)
        assertEquals(listOf(true, false, false), preview.rows.map { it.duplicate })
    }

    @Test
    fun twoIdenticalPurchasesOneAlreadyRecorded() {
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        val preview = ImportPreviewBuilder.build("2026/9/4,7,娱-彩票,a\n2026/9/4,7.00,娱-彩票,b", categories) { _, _ -> mapOf(key to 1) }

        assertEquals(listOf(true, false), preview.rows.map { it.duplicate })
    }

    @Test
    fun invalidRowsAreNeverDuplicates() {
        val rows = ImportRowParser.parse(CsvReader.read("2026/9/4,abc,娱-彩票\n2026/9/4,7,娱-彩票"), categories)
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        assertEquals(listOf(false, true), DuplicateFlagger.flag(rows, mapOf(key to 5)))
    }

    @Test
    fun reImportingTheSameFileFlagsEveryRow() = runTest {
        val db = TestDatabases.ledger().second
        val repo = TransactionRepository(db, TickingClock(Instant.parse("2026-10-01T00:00:00Z")), Dispatchers.Unconfined)
        val seeded = db.ledgerQueries.selectAllCategories().executeAsList().map { Category(it.id, it.name) }
        val text = "2026/9/4,7,娱-彩票\n2026/9/4,7,娱-彩票\n2026/9/5,124.01,食-食材,Costco\n2026/9/30,3500,IN - Sa"

        val first = ImportPreviewBuilder.build(text, seeded, repo::countMatching)
        assertEquals(0, first.duplicateCount)
        repo.importAll(first.rows.map { it.toInput()!! })

        val again = ImportPreviewBuilder.build(text, seeded, repo::countMatching)
        assertEquals(4, again.duplicateCount)
        assertEquals(List(4) { true }, again.rows.map { it.duplicate })
    }
}
