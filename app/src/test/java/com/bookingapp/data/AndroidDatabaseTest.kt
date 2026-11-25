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
