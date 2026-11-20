package com.bookingapp.data.reload

import com.bookingapp.data.reload.LedgerFileValidator.Result
import com.bookingapp.testing.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/** JDBC implementation of [SqlReader], so the checks run on the JVM against real SQLite files. */
class JdbcSqlReader(file: File) : SqlReader {
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${file.absolutePath}",
        Properties().apply { put("open_mode", "1") }, // SQLITE_OPEN_READONLY
    )

    override fun query(sql: String): List<List<String?>> = connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add(List(columns) { rs.getString(it + 1) }) }
        }
    }

    override fun close() = connection.close()
}

class LedgerFileValidatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val validator = LedgerFileValidator(::JdbcSqlReader)

    /** A backend-built ledger file: Flyway V1 + V2, optional history, three transactions. */
    private fun backendFile(
        flywayVersions: List<Pair<String, Boolean>>? = listOf("1" to true, "2" to true),
        v1: (String) -> String = { it },
        orphan: Boolean = false,
    ): File {
        val file = temp.newFile("bookingapp-${System.nanoTime()}.db").also { it.delete() }
        TestDatabases.jdbc(file).use { c ->
            TestDatabases.runScript(c, v1(TestDatabases.referenceSql("V1__create_schema.sql")))
            TestDatabases.runScript(c, TestDatabases.referenceSql("V2__seed_categories.sql"))
            c.createStatement().use { st ->
                listOf("2025-01-03" to 1999, "2026-03-05" to 500, "2026-11-12" to 700).forEach { (date, amount) ->
                    st.execute(
                        "INSERT INTO transaction_entry (type, amount_minor, entry_date, category_id, note, created_at, updated_at) " +
                            "VALUES ('COST', $amount, '$date', 5, NULL, '${date}T00:00:00.000000Z', '${date}T00:00:00.000000Z')",
                    )
                }
                if (flywayVersions != null) {
                    st.execute(
                        "CREATE TABLE flyway_schema_history (installed_rank INT NOT NULL PRIMARY KEY, version VARCHAR(50), " +
                            "description VARCHAR(200) NOT NULL, type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, " +
                            "checksum INT, installed_by VARCHAR(100) NOT NULL, installed_on TEXT NOT NULL DEFAULT '', " +
                            "execution_time INT NOT NULL, success BOOLEAN NOT NULL)",
                    )
                    flywayVersions.forEachIndexed { i, (version, success) ->
                        st.execute(
                            "INSERT INTO flyway_schema_history VALUES (${i + 1}, '$version', 'm', 'SQL', 'V$version.sql', 0, 'x', '', 1, ${if (success) 1 else 0})",
                        )
                    }
                }
            }
        }
        if (orphan) {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.createStatement().execute(
                    "INSERT INTO transaction_entry (type, amount_minor, entry_date, category_id, note, created_at, updated_at) " +
                        "VALUES ('COST', 1, '2026-01-01', 999, NULL, 't', 't')",
                )
            }
        }
        return file
    }

    private fun rejection(file: File): Rejection = (validator.validate(file) as Result.Rejected).reason

    @Test
    fun validFileWithFlywayHistory() {
        val result = validator.validate(backendFile())

        assertEquals(
            Result.Valid(LedgerFileSummary(3, 32, LocalDate.of(2025, 1, 3), LocalDate.of(2026, 11, 12))),
            result,
        )
    }

    @Test
    fun validFileWithoutHistoryOrTransactions() {
        val file = temp.newFile("empty.db").also { it.delete() }
        TestDatabases.backendDatabase(file).close()

        assertEquals(Result.Valid(LedgerFileSummary(0, 32, null, null)), validator.validate(file))
    }

    @Test
    fun failedNewerMigrationIsIgnored() {
        assertTrue(validator.validate(backendFile(listOf("1" to true, "2" to true, "3" to false))) is Result.Valid)
    }

    @Test
    fun textFileIsNotSqlite() {
        val file = temp.newFile("import.csv").apply { writeText("2026/9/4,$27.25,住-日杂\n") }
        assertEquals(Rejection.NotSqlite, rejection(file))
    }

    @Test
    fun emptyFileIsNotSqlite() {
        assertEquals(Rejection.NotSqlite, rejection(temp.newFile("empty.bin")))
    }

    @Test
    fun newerFlywayVersionRejected() {
        assertEquals(Rejection.NewerSchema("3"), rejection(backendFile(listOf("1" to true, "2" to true, "3" to true))))
        assertEquals(Rejection.NewerSchema("2.1"), rejection(backendFile(listOf("1" to true, "2.1" to true))))
    }

    @Test
    fun orphanCategoryIdRejected() {
        assertEquals(Rejection.BrokenCategoryLink, rejection(backendFile(orphan = true)))
    }

    @Test
    fun alteredColumnRejected() {
        val file = backendFile(v1 = { it.replace("note         TEXT,", "note         TEXT,\n    payee        TEXT,") })
        assertEquals(Rejection.SchemaMismatch, rejection(file))
    }

    @Test
    fun missingIndexRejected() {
        val file = backendFile(v1 = { it.replace(Regex("CREATE INDEX[^;]*;"), "") })
        assertEquals(Rejection.SchemaMismatch, rejection(file))
    }

    @Test
    fun damagedFileRejected() {
        val file = temp.newFile("damaged.db")
        file.writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(4096) { (it * 31).toByte() })
        assertEquals(Rejection.Corrupt, rejection(file))
    }

    @Test
    fun fileOver100MegabytesRejected() {
        val file = temp.newFile("huge.db")
        RandomAccessFile(file, "rw").use { it.setLength(LedgerFileValidator.MAX_BYTES + 1) }
        assertEquals(Rejection.TooLarge, rejection(file))
    }

    @Test
    fun comparesFlywayVersionsNumerically() {
        assertTrue(LedgerFileValidator.compareVersions("10", "2") > 0)
        assertTrue(LedgerFileValidator.compareVersions("2.1", "2") > 0)
        assertEquals(0, LedgerFileValidator.compareVersions("2.0", "2"))
        assertTrue(LedgerFileValidator.compareVersions("1", "2") < 0)
    }
}

###LedgerReplacerTest.kt
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

###../AndroidDatabaseTest.kt
package com.bookingapp.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** The Android driver from DatabaseFactory: foreign keys, journal mode, schema and seeding. */
@RunWith(AndroidJUnit4::class)
class AndroidDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun SqlDriver.single(sql: String): String? =
        executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value

    @Test
    fun opensWithForeignKeysOnAndNoWal() {
        val driver = DatabaseFactory.createDriver(context)
        try {
            assertEquals("1", driver.single("PRAGMA foreign_keys"))
            assertNotEquals("wal", driver.single("PRAGMA journal_mode")?.lowercase())
            val db = DatabaseFactory.createDatabase(driver)
            assertThrows(Exception::class.java) {
                db.ledgerQueries.insertTransaction("COST", 100, "2026-10-01", 9999, null, "t", "t")
            }
            assertEquals(0L, db.ledgerQueries.selectInRange("0000", "9999", null).executeAsList().size.toLong())
        } finally {
            driver.close()
        }
        assertEquals(DatabaseFactory.DATABASE_NAME, context.getDatabasePath(DatabaseFactory.DATABASE_NAME).name)
    }

    @Test
    fun schemaMatchesBackendDdl() {
        val driver = DatabaseFactory.createDriver(context)
        val ddl = driver.executeQuery(null, "SELECT name, sql FROM sqlite_master WHERE name IN ('category','transaction_entry','ix_transaction_entry_date_created')", { c ->
            QueryResult.Value(buildMap { while (c.next().value) put(c.getString(0)!!, LedgerSchema.normalise(c.getString(1)!!)) })
        }, 0).value
        driver.close()
        assertEquals(LedgerSchema.EXPECTED_DDL.mapValues { LedgerSchema.normalise(it.value) }, ddl)
    }

    @Test
    fun relaunchDoesNotDuplicateCategories() {
        repeat(3) {
            val driver = DatabaseFactory.createDriver(context)
            val names = DatabaseFactory.createDatabase(driver).ledgerQueries.selectAllCategories().executeAsList().map { it.name }
            assertEquals(32, names.size)
            assertEquals(names.sorted(), names)
            driver.close()
        }
    }

    @Test
    fun dataSurvivesReopening() {
        DatabaseFactory.createDriver(context).let { driver ->
            DatabaseFactory.createDatabase(driver).ledgerQueries.insertTransaction("COST", 1999, "2026-11-04", 1, null, "t", "t")
            driver.close()
        }
        val file = SQLiteDatabase.openDatabase(context.getDatabasePath(DatabaseFactory.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY)
        file.rawQuery("SELECT entry_date, typeof(entry_date), amount_minor, typeof(amount_minor) FROM transaction_entry", null).use {
            it.moveToFirst()
            assertEquals(listOf("2026-11-04", "text", "1999", "integer"), List(4) { i -> it.getString(i) })
        }
        file.close()
    }
}

###../CategoryRepositoryTest.kt
package com.bookingapp.data

import com.bookingapp.testing.TestDatabases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryRepositoryTest {

    @Test
    fun observeAllIsAlphabetical() = runTest {
        val repo = CategoryRepository(TestDatabases.ledger().second, Dispatchers.Unconfined)

        val names = repo.observeAll().first().map { it.name }

        assertEquals(32, names.size)
        assertEquals(names.sorted(), names)
        assertEquals("CL - Apparel", names.first())
        assertEquals("TR - Repairs", names.last())
    }
}

###../SchemaParityTest.kt
package com.bookingapp.data

import app.cash.sqldelight.db.QueryResult
import com.bookingapp.testing.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.sql.Connection

class SchemaParityTest {

    private fun backendMaster(connection: Connection): List<Pair<String, String>> =
        connection.createStatement().use { st ->
            st.executeQuery(MASTER_SQL).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to LedgerSchema.normalise(rs.getString(2))) }
            }
        }

    private fun sqldelightMaster(): List<Pair<String, String>> {
        val driver = TestDatabases.driver()
        return driver.executeQuery(null, MASTER_SQL, { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) add(cursor.getString(0)!! to LedgerSchema.normalise(cursor.getString(1)!!))
            })
        }, 0).value
    }

    @Test
    fun sqldelightSchemaMatchesFlywayV1() {
        val backend = TestDatabases.backendDatabase().use(::backendMaster)

        assertEquals(backend, sqldelightMaster())
        // The DDL the reload check expects is the same pinned schema.
        val ledgerObjects = backend.filter { it.first.substringAfter(':') in LedgerSchema.EXPECTED_DDL }
            .associate { it.first.substringAfter(':') to it.second }
        assertEquals(3, ledgerObjects.size)
        assertEquals(ledgerObjects, LedgerSchema.EXPECTED_DDL.mapValues { LedgerSchema.normalise(it.value) })
    }

    @Test
    fun seededCategoriesPresentExactlyOnce() {
        val (_, db) = TestDatabases.ledger()
        val names = db.ledgerQueries.selectAllCategories().executeAsList().map { it.name }
        val seeded = Regex("\\('([^']*)'\\)").findAll(TestDatabases.referenceSql("V2__seed_categories.sql"))
            .map { it.groupValues[1] }.toList()

        assertEquals(32, names.size)
        assertEquals(seeded.sorted(), names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun orphanCategoryIdRejected() {
        val (_, db) = TestDatabases.ledger()

        assertThrows(Exception::class.java) {
            db.ledgerQueries.insertTransaction("COST", 100, "2026-10-01", 9999, null, "t", "t")
        }
        assertEquals(0L, db.ledgerQueries.selectInRange("0000", "9999", null).executeAsList().size.toLong())
    }

    private companion object {
        /** Every schema object, including SQLite's own (sqlite_sequence, the UNIQUE autoindex). */
        const val MASTER_SQL = "SELECT type || ':' || name, COALESCE(sql, '') FROM sqlite_master ORDER BY type, name"
    }
}

###../TransactionRepositoryTest.kt  
package com.bookingapp.data

import app.cash.turbine.test
import com.bookingapp.data.db.LedgerDatabase
import com.bookingapp.domain.Field
import com.bookingapp.domain.TransactionInput
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.TransactionType.COST
import com.bookingapp.domain.TransactionType.EARNING
import com.bookingapp.domain.ValidationException
import com.bookingapp.testing.TestDatabases
import com.bookingapp.testing.TickingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class TransactionRepositoryTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: TransactionRepository
    private lateinit var categories: Map<String, Long>

    @Before
    fun setUp() {
        db = TestDatabases.ledger().second
        repo = TransactionRepository(db, TickingClock(Instant.parse("2026-10-20T08:00:00Z")), Dispatchers.Unconfined)
        categories = db.ledgerQueries.selectAllCategories().executeAsList().associate { it.name to it.id }
    }

    private fun input(amount: Long, date: String, category: String = "FO - Groceries", type: TransactionType = COST, note: String? = null) =
        TransactionInput(type, amount, LocalDate.parse(date), categories.getValue(category), note)

    private suspend fun add(amount: Long, date: String, category: String = "FO - Groceries", type: TransactionType = COST) =
        repo.create(input(amount, date, category, type))

    @Test
    fun recordACost() = runTest {
        val id = repo.create(input(1999, "2026-10-05", note = "Weekly shop"))

        val saved = repo.get(id)!!
        assertEquals(COST, saved.type)
        assertEquals(1999L, saved.amountMinor)
        assertEquals(LocalDate.of(2026, 10, 5), saved.date)
        assertEquals("FO - Groceries", saved.categoryName)
        assertEquals("Weekly shop", saved.note)
        assertEquals(saved.createdAt, saved.updatedAt)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z""").matches(saved.createdAt))
    }

    @Test
    fun dateStoredAsTextAndAmountAsInteger() = runTest {
        add(1999, "2026-11-04")

        val row = db.ledgerQueries.selectInRange("2026-11-01", "2026-12-01", null).executeAsOne()
        assertEquals("2026-11-04", row.entry_date)
        assertEquals(1999L, row.amount_minor)
    }

    @Test
    fun updateKeepsCreatedAt() = runTest {
        val id = add(1999, "2026-10-05")
        val before = repo.get(id)!!

        repo.update(id, input(2099, "2026-10-05"))

        val after = repo.get(id)!!
        assertEquals(2099L, after.amountMinor)
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt >= before.updatedAt)
    }

    @Test
    fun costCanBecomeEarning() = runTest {
        val id = add(1999, "2026-10-05")
        repo.update(id, input(1999, "2026-10-05", "IN - Bo", EARNING))
        assertEquals(EARNING, repo.get(id)!!.type)
    }

    @Test
    fun rejectionsNameTheFieldAndSaveNothing() = runTest {
        suspend fun rejected(input: TransactionInput): Set<Field> = try {
            repo.create(input)
            fail("expected rejection")
            emptySet()
        } catch (e: ValidationException) {
            e.errors.keys
        }

        assertEquals(setOf(Field.NOTE), rejected(input(1, "2026-10-05", note = "x".repeat(501))))
        assertEquals(setOf(Field.AMOUNT), rejected(input(0, "2026-10-05")))
        assertEquals(setOf(Field.AMOUNT), rejected(input(-5, "2026-10-05")))
        assertEquals(setOf(Field.AMOUNT), rejected(input(9_007_199_254_740_992, "2026-10-05")))
        assertEquals(setOf(Field.CATEGORY), rejected(input(1, "2026-10-05").copy(categoryId = 9999)))
        assertTrue(repo.observeMonth(YearMonth.of(2026, 10)).first().isEmpty())
    }

    @Test
    fun blankNoteStoredAsNull() = runTest {
        val id = repo.create(input(1, "2026-10-05", note = "   "))
        assertNull(repo.get(id)!!.note)
    }

    @Test
    fun deleteRemovesFromListsAndTotals() = runTest {
        val id = add(1999, "2026-10-05")
        repo.delete(id)

        assertNull(repo.get(id))
        assertTrue(repo.observeMonth(YearMonth.of(2026, 10)).first().isEmpty())
        assertEquals(0L, repo.observeSummary(YearMonth.of(2026, 10)).first().outcomeMinor)
    }

    @Test
    fun monthBoundariesRespected() = runTest {
        add(1, "2026-09-30"); add(2, "2026-10-01"); add(3, "2026-10-31"); add(4, "2026-11-01")

        val listed = repo.observeMonth(YearMonth.of(2026, 10)).first().map { it.date.toString() }
        assertEquals(listOf("2026-10-31", "2026-10-01"), listed)
    }

    @Test
    fun sameDayNewestCreatedFirst() = runTest {
        val first = add(1, "2026-10-05")
        val second = add(2, "2026-10-05")

        assertEquals(listOf(second, first), repo.observeMonth(YearMonth.of(2026, 10)).first().map { it.id })
    }

    @Test
    fun typeFilter() = runTest {
        add(1, "2026-10-05"); add(2, "2026-10-06", "IN - Sa", EARNING)

        assertEquals(listOf(1L), repo.observeMonth(YearMonth.of(2026, 10), COST).first().map { it.amountMinor })
        assertEquals(listOf(2L), repo.observeMonth(YearMonth.of(2026, 10), EARNING).first().map { it.amountMinor })
        assertEquals(2, repo.observeMonth(YearMonth.of(2026, 10)).first().size)
    }

    @Test
    fun mixedMonthSummary() = runTest {
        add(350000, "2026-10-01", "IN - Sa", EARNING); add(5000, "2026-10-02", "IN - Bo", EARNING)
        add(1999, "2026-10-03"); add(12000, "2026-10-04", "HO - Phone & Internet"); add(4500, "2026-10-05", "EN - Sports")

        val summary = repo.observeSummary(YearMonth.of(2026, 10)).first()
        assertEquals(355000L, summary.incomeMinor)
        assertEquals(18499L, summary.outcomeMinor)
        assertEquals(336501L, summary.balanceMinor)
        // Costs first, then amount descending.
        assertEquals(
            listOf("HO - Phone & Internet", "EN - Sports", "FO - Groceries", "IN - Sa", "IN - Bo"),
            summary.breakdown.map { it.categoryName },
        )
        // The breakdown reconciles with the totals.
        assertEquals(summary.outcomeMinor, summary.breakdown.filter { it.type == COST }.sumOf { it.totalMinor })
        assertEquals(summary.incomeMinor, summary.breakdown.filter { it.type == EARNING }.sumOf { it.totalMinor })
    }

    @Test
    fun breakdownTiesOrderedByName() = runTest {
        add(100, "2026-10-01", "HO - Plants"); add(100, "2026-10-01", "HO - Gifts")
        assertEquals(
            listOf("HO - Gifts", "HO - Plants"),
            repo.observeSummary(YearMonth.of(2026, 10)).first().breakdown.map { it.categoryName },
        )
    }

    @Test
    fun yearSpansMonthsAndExcludesAdjacentYears() = runTest {
        add(999, "2025-12-31"); add(999, "2027-01-01"); add(500, "2026-03-05"); add(700, "2026-12-31")

        assertEquals(1200L, repo.observeSummary(Year.of(2026)).first().outcomeMinor)
    }

    @Test
    fun emptyPeriod() = runTest {
        val summary = repo.observeSummary(YearMonth.of(2026, 2)).first()
        assertEquals(0L, summary.incomeMinor)
        assertEquals(0L, summary.outcomeMinor)
        assertEquals(0L, summary.balanceMinor)
        assertTrue(summary.breakdown.isEmpty())
    }

    @Test
    fun yearRangeLimits() {
        assertThrows(IllegalArgumentException::class.java) { DateRange.ofYear(0) }
        assertThrows(IllegalArgumentException::class.java) { DateRange.ofYear(10000) }
        val last = DateRange.ofYear(9999)
        assertTrue("9999-12-31" >= last.from && "9999-12-31" < last.to)
    }

    @Test
    fun tenDimesSumWithoutDrift() = runTest {
        repeat(10) { add(10, "2026-10-05") }
        assertEquals(100L, repo.observeSummary(YearMonth.of(2026, 10)).first().outcomeMinor)
    }

    @Test
    fun importSavesAllInOneTransaction() = runTest {
        val rows = (1..22).map { input(it * 100L, "2026-09-%02d".format(it)) }

        assertEquals(22, repo.importAll(rows))
        assertEquals(22, repo.observeMonth(YearMonth.of(2026, 9)).first().size)
    }

    @Test
    fun importFailingPartwaySavesNothing() = runTest {
        val rows = (1..10).map { input(100, "2026-09-0${it % 9 + 1}") }.toMutableList()
        rows[6] = rows[6].copy(categoryId = 9999)

        assertThrows(ValidationException::class.java) { kotlinx.coroutines.runBlocking { repo.importAll(rows) } }
        assertTrue(repo.observeMonth(YearMonth.of(2026, 9)).first().isEmpty())
    }

    @Test
    fun importRejectsEmptyAndOversizedBatches() = runTest {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.importAll(emptyList()) } }
        val tooMany = List(5001) { input(1, "2026-09-01") }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.importAll(tooMany) } }
    }

    @Test
    fun summaryFlowUpdatesOnWrite() = runTest {
        repo.observeSummary(YearMonth.of(2026, 10)).test {
            assertEquals(0L, awaitItem().outcomeMinor)
            add(5000, "2026-10-05")
            assertEquals(5000L, awaitItem().outcomeMinor)
            add(2000, "2026-10-06")
            assertEquals(7000L, awaitItem().outcomeMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
