package com.bookingapp.data.reload

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.AppContainer
import com.bookingapp.data.DatabaseFactory
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Year

/**
 * The android-ledger reload scenarios against real files and the platform SQLite. (The task
 * list asks for an instrumented test; it runs here under Robolectric so it needs no device.)
 */
@RunWith(AndroidJUnit4::class)
class LedgerReplacerTest {

    private val app = AppTesting.app
    private val dbFile: File = app.getDatabasePath(DatabaseFactory.DATABASE_NAME)

    private fun backendFile(vararg rows: Triple<String, Long, String>) =
        Fixtures.backendLedger(File(app.filesDir, "backend-${System.nanoTime()}.db"), rows.toList())

    private fun AppContainer.amounts(year: Int): List<Long> =
        runBlocking { transactions.observeSummary(Year.of(year)).first().breakdown.map { it.totalMinor } }

    private fun AppContainer.allRows() = runBlocking {
        (1..12).flatMap { transactions.observeMonth(java.time.YearMonth.of(2026, it)).first() } +
                (1..12).flatMap { transactions.observeMonth(java.time.YearMonth.of(2025, it)).first() }
    }

    private fun reload(c: AppContainer, file: File) {
        val replacer = c.ledgerReplacer!!
        replacer.copyIn(file.inputStream())
        assertTrue(replacer.check() is LedgerFileValidator.Result.Valid)
        replacer.replace()
    }

    @Test
    fun reloadReplacesRatherThanMerges() {
        val c = AppTesting.container()
        c.add(4242, "2026-10-05")
        val file = backendFile(Triple("2025-01-03", 1999L, "FO - Groceries"), Triple("2026-11-12", 500L, "TR - Gas"))

        reload(c, file)

        val rows = c.allRows()
        assertEquals(listOf(500L, 1999L), rows.map { it.amountMinor }.sorted())
        assertFalse(rows.any { it.amountMinor == 4242L })
        // The file's ids, notes and timestamps are kept as they are.
        val gas = rows.single { it.amountMinor == 500L }
        assertEquals("2026-11-12T09:00:00.123456Z", gas.createdAt)
        assertEquals("from PC", gas.note)
        assertTrue(c.ledgerReplacer!!.hasBackup.value)
        // The new ledger accepts writes and keeps its seeded categories exactly once.
        c.add(100, "2026-11-13")
        assertEquals(32, runBlocking { c.categories.getAll() }.size)
    }

    @Test
    fun reloadedFileOpensAsAnExistingLedgerNextLaunch() {
        val c = AppTesting.container()
        reload(c, backendFile(Triple("2026-03-05", 700L, "FO - Groceries")))
        c.close()

        val relaunched = AppTesting.container()
        assertEquals(listOf(700L), relaunched.amounts(2026))
    }

    @Test
    fun failureDuringTheSwapLeavesTheLedgerUnchanged() {
        listOf(LedgerReplacer.Step.STAMP, LedgerReplacer.Step.BACKUP, LedgerReplacer.Step.MOVE_IN, LedgerReplacer.Step.REOPEN).forEach { step ->
            dbFile.delete()
            File(dbFile.path + ".bak").delete()
            var failAt: LedgerReplacer.Step? = step
            val c = AppTesting.container { if (it == failAt) throw IOException("No space left on device") }
            c.add(4242, "2026-10-05")
            val replacer = c.ledgerReplacer!!
            replacer.copyIn(backendFile(Triple("2025-01-03", 1999L, "FO - Groceries")).inputStream())

            assertThrows(step.name, IOException::class.java) { replacer.replace() }

            assertEquals(step.name, listOf(4242L), c.allRows().map { it.amountMinor })
            assertFalse(step.name, replacer.hasBackup.value)
            assertFalse(step.name, File(dbFile.path + ".bak").exists())
            failAt = null
            c.add(1, "2026-10-06") // still writable
            c.close()
        }
    }

    @Test
    fun failedReloadKeepsAnEarlierBackup() {
        var fail = false
        val c = AppTesting.container { if (fail && it == LedgerReplacer.Step.REOPEN) throw IOException("boom") }
        c.add(1111, "2026-10-05")
        reload(c, backendFile(Triple("2026-01-01", 2222L, "FO - Groceries")))

        fail = true
        c.ledgerReplacer!!.copyIn(backendFile(Triple("2026-01-01", 3333L, "FO - Groceries")).inputStream())
        assertThrows(IOException::class.java) { c.ledgerReplacer!!.replace() }
        fail = false

        assertEquals(listOf(2222L), c.allRows().map { it.amountMinor })
        c.ledgerReplacer!!.restorePrevious()
        assertEquals(listOf(1111L), c.allRows().map { it.amountMinor })
    }

    @Test
    fun restorePutsTheOldLedgerBackAndDeletesTheBackup() {
        val c = AppTesting.container()
        c.add(4242, "2026-10-05")
        reload(c, backendFile(Triple("2025-01-03", 1999L, "FO - Groceries")))

        c.ledgerReplacer!!.restorePrevious()

        assertEquals(listOf(4242L), c.allRows().map { it.amountMinor })
        assertFalse(c.ledgerReplacer!!.hasBackup.value)
        assertFalse(File(dbFile.path + ".bak").exists())
    }

    @Test
    fun laterReloadReplacesTheBackup() {
        val c = AppTesting.container()
        c.add(1111, "2026-10-05")
        reload(c, backendFile(Triple("2026-01-01", 2222L, "FO - Groceries")))
        reload(c, backendFile(Triple("2026-01-01", 3333L, "FO - Groceries")))

        c.ledgerReplacer!!.restorePrevious()

        assertEquals(listOf(2222L), c.allRows().map { it.amountMinor })
    }

    @Test
    fun noRestoreWithoutABackup() {
        val c = AppTesting.container()
        assertFalse(c.ledgerReplacer!!.hasBackup.value)
        assertThrows(IllegalStateException::class.java) { c.ledgerReplacer!!.restorePrevious() }
    }

    @Test
    fun startupRecoversAMissingLedgerFromTheBackup() {
        val c = AppTesting.container()
        c.add(4242, "2026-10-05")
        c.close()
        // Simulate the app being killed between the renames: only the backup exists.
        assertTrue(dbFile.renameTo(File(dbFile.path + ".bak")))

        val relaunched = AppTesting.container()

        assertEquals(listOf(4242L), relaunched.allRows().map { it.amountMinor })
    }

    @Test
    fun copyStopsAfter100Megabytes() {
        val c = AppTesting.container()
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        assertThrows(FileTooLargeException::class.java) { c.ledgerReplacer!!.copyIn(endless) }
        assertFalse(c.ledgerReplacer!!.incomingFile.exists())
    }

    @Test
    fun androidReaderRejectsNonBackendFiles() {
        val c = AppTesting.container()
        val replacer = c.ledgerReplacer!!
        replacer.copyIn("2026/9/4,$27.25,住-日杂\n".byteInputStream())
        assertEquals(LedgerFileValidator.Result.Rejected(Rejection.NotSqlite), replacer.check())

        val newer = Fixtures.backendLedger(File(app.filesDir, "v3.db"), flywayVersion = "3")
        replacer.copyIn(newer.inputStream())
        assertEquals(LedgerFileValidator.Result.Rejected(Rejection.NewerSchema("3")), replacer.check())
    }
}
