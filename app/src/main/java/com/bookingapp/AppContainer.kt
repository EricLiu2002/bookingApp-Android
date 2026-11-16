package com.bookingapp

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.bookingapp.data.CategoryRepository
import com.bookingapp.data.DatabaseFactory
import com.bookingapp.data.TransactionRepository
import com.bookingapp.data.db.LedgerDatabase
import com.bookingapp.data.reload.AndroidSqlReader
import com.bookingapp.data.reload.LedgerFileValidator
import com.bookingapp.data.reload.LedgerHandle
import com.bookingapp.data.reload.LedgerReplacer
import com.bookingapp.ui.importing.ContentResolverDocuments
import com.bookingapp.ui.importing.DocumentReader
import java.time.Clock

/**
 * Manual dependency wiring (design decision 9). ViewModels ask for repositories when they
 * are created and never keep a driver across a ledger reload, which swaps the file and
 * calls [reopen].
 *
 * @param databaseName the ledger file name, or null for an in-memory ledger (tests).
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    private val databaseName: String? = DatabaseFactory.DATABASE_NAME,
    val currency: String = BuildConfig.CURRENCY,
    replacerHook: (LedgerReplacer.Step) -> Unit = {},
) : LedgerHandle {

    private val appContext = context.applicationContext
    private var driver: SqlDriver? = null
    private var database: LedgerDatabase? = null
    private var transactionRepository: TransactionRepository? = null
    private var categoryRepository: CategoryRepository? = null

    var documents: DocumentReader = ContentResolverDocuments(appContext.contentResolver)

    val ledgerReplacer: LedgerReplacer? = databaseName?.let { name ->
        LedgerReplacer(
            databaseFile = appContext.getDatabasePath(name),
            cacheDir = appContext.cacheDir,
            validator = LedgerFileValidator(::AndroidSqlReader),
            stampVersion = AndroidSqlReader::stampUserVersion,
            schemaVersion = DatabaseFactory.SCHEMA_VERSION,
            ledger = this,
            beforeStep = replacerHook,
        )
    }

    init {
        databaseName?.let { LedgerReplacer.recover(appContext.getDatabasePath(it)) }
        open()
    }

    val transactions: TransactionRepository
        @Synchronized get() = transactionRepository ?: open().let { transactionRepository!! }

    val categories: CategoryRepository
        @Synchronized get() = categoryRepository ?: open().let { categoryRepository!! }

    @Synchronized
    private fun open() {
        val newDriver = DatabaseFactory.createDriver(appContext, databaseName)
        val db = DatabaseFactory.createDatabase(newDriver)
        driver = newDriver
        database = db
        transactionRepository = TransactionRepository(db, clock)
        categoryRepository = CategoryRepository(db)
    }

    @Synchronized
    override fun close() {
        driver?.close()
        driver = null
        database = null
        transactionRepository = null
        categoryRepository = null
    }

    @Synchronized
    override fun reopen() {
        close()
        open()
        try {
            // Smoke query: the ledger tables can be read.
            database!!.ledgerQueries.selectAllCategories().executeAsList()
            database!!.ledgerQueries.periodTotals("0000-01-01", "9999-12-31").executeAsOne()
        } catch (e: Exception) {
            close()
            throw e
        }
    }
}
