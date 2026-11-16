package com.bookingapp.data.reload

import com.bookingapp.data.LedgerSchema
import java.io.Closeable
import java.io.File
import java.time.LocalDate

/** Read-only SQL access to a candidate database file (Android or JDBC). */
interface SqlReader : Closeable {
    /** Runs [sql] and returns every row, each column as text (or null). */
    fun query(sql: String): List<List<String?>>
}

/** Why a chosen file can't replace the ledger. */
sealed interface Rejection {
    data object TooLarge : Rejection
    data object NotSqlite : Rejection
    data object Corrupt : Rejection
    data object SchemaMismatch : Rejection
    data class NewerSchema(val version: String) : Rejection
    data object BrokenCategoryLink : Rejection
}

/** What the confirmation dialog shows about a valid file. */
data class LedgerFileSummary(
    val transactionCount: Long,
    val categoryCount: Long,
    val earliest: LocalDate?,
    val latest: LocalDate?,
)

/**
 * Checks a backend `bookingapp.db` before it may replace the phone ledger (design decision
 * 15, check step). Tables other than the ledger's, such as Flyway's history, are ignored.
 */
class LedgerFileValidator(private val open: (File) -> SqlReader) {

    sealed interface Result {
        data class Valid(val summary: LedgerFileSummary) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    fun validate(file: File): Result {
        if (file.length() > MAX_BYTES) return Result.Rejected(Rejection.TooLarge)
        if (!hasSqliteHeader(file)) return Result.Rejected(Rejection.NotSqlite)
        val reader = try {
            open(file)
        } catch (e: Exception) {
            return Result.Rejected(Rejection.NotSqlite)
        }
        return try {
            reader.use { check(it) }
        } catch (e: Exception) {
            // A file with a valid header that SQLite can't read is damaged.
            Result.Rejected(Rejection.Corrupt)
        }
    }

    private fun check(db: SqlReader): Result {
        val integrity = db.query("PRAGMA integrity_check")
        if (integrity.size != 1 || integrity[0].firstOrNull() != "ok") return Result.Rejected(Rejection.Corrupt)

        newestFlywayVersion(db)?.let { newest ->
            if (compareVersions(newest, LedgerSchema.SUPPORTED_FLYWAY_VERSION.toString()) > 0) {
                return Result.Rejected(Rejection.NewerSchema(newest))
            }
        }

        val names = LedgerSchema.EXPECTED_DDL.keys.joinToString(",") { "'$it'" }
        val actual = db.query("SELECT name, sql FROM sqlite_master WHERE name IN ($names)")
            .associate { it[0]!! to LedgerSchema.normalise(it[1].orEmpty()) }
        if (actual != LedgerSchema.EXPECTED_DDL.mapValues { LedgerSchema.normalise(it.value) }) {
            return Result.Rejected(Rejection.SchemaMismatch)
        }

        if (db.query("PRAGMA foreign_key_check").isNotEmpty()) return Result.Rejected(Rejection.BrokenCategoryLink)

        val counts = db.query(
            "SELECT (SELECT COUNT(*) FROM transaction_entry), (SELECT COUNT(*) FROM category), " +
                "(SELECT MIN(entry_date) FROM transaction_entry), (SELECT MAX(entry_date) FROM transaction_entry)",
        ).single()
        return Result.Valid(
            LedgerFileSummary(
                transactionCount = counts[0]!!.toLong(),
                categoryCount = counts[1]!!.toLong(),
                earliest = counts[2]?.let(LocalDate::parse),
                latest = counts[3]?.let(LocalDate::parse),
            ),
        )
    }

    /** The newest successful Flyway migration, or null when the file has no Flyway history. */
    private fun newestFlywayVersion(db: SqlReader): String? {
        val hasHistory = db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'",
        ).single()[0] != "0"
        if (!hasHistory) return null
        return db.query("SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")
            .mapNotNull { it[0] }
            .maxWithOrNull(::compareVersions)
    }

    companion object {
        const val MAX_BYTES: Long = 100L * 1024 * 1024
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        fun hasSqliteHeader(file: File): Boolean = try {
            file.inputStream().use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
            }
        } catch (e: Exception) {
            false
        }

        /** Compares Flyway versions such as "2", "2.1" and "10" part by part, numerically. */
        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.', '_').map { it.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }
            val right = b.split('.', '_').map { it.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }
            for (i in 0 until maxOf(left.size, right.size)) {
                val cmp = left.getOrElse(i) { java.math.BigInteger.ZERO }.compareTo(right.getOrElse(i) { java.math.BigInteger.ZERO })
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
