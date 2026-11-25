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
