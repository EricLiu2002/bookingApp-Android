package com.bookingapp.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.bookingapp.data.db.LedgerDatabase

/** Opens the phone's ledger (design decision 3). */
object DatabaseFactory {

    const val DATABASE_NAME = "bookingapp.db"

    /** SQLDelight's schema version; a reloaded backend file is stamped with it. */
    val SCHEMA_VERSION: Long get() = LedgerDatabase.Schema.version

    /**
     * @param name the file name in the app's database folder, or null for an in-memory ledger.
     */
    fun createDriver(context: Context, name: String? = DATABASE_NAME): SqlDriver =
        AndroidSqliteDriver(
            schema = LedgerDatabase.Schema,
            context = context,
            name = name,
            callback = object : AndroidSqliteDriver.Callback(LedgerDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                    // Rollback journal, not WAL: a committed file is complete on its own.
                    db.disableWriteAheadLogging()
                }
            },
        )

    fun createDatabase(driver: SqlDriver): LedgerDatabase = LedgerDatabase(driver)
}
