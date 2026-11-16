package com.bookingapp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bookingapp.data.db.LedgerDatabase
import com.bookingapp.domain.Category
import com.bookingapp.domain.CategoryTotal
import com.bookingapp.domain.Summary
import com.bookingapp.domain.Transaction
import com.bookingapp.domain.TransactionInput
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.TransactionValidator
import com.bookingapp.domain.ValidationException
import com.bookingapp.domain.importing.DuplicateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import kotlin.coroutines.CoroutineContext

/** Monthly and yearly summaries; an interface so screens can be tested with failing sources. */
interface SummarySource {
    fun observeSummary(month: YearMonth): Flow<Summary>
    fun observeSummary(year: Year): Flow<Summary>
}

class CategoryRepository(
    private val db: LedgerDatabase,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** All categories, alphabetically by name. */
    fun observeAll(): Flow<List<Category>> =
        db.ledgerQueries.selectAllCategories { id, name -> Category(id, name) }.asFlow().mapToList(io)

    suspend fun getAll(): List<Category> = withContext(io) {
        db.ledgerQueries.selectAllCategories { id, name -> Category(id, name) }.executeAsList()
    }
}

class TransactionRepository(
    private val db: LedgerDatabase,
    private val clock: Clock,
    private val io: CoroutineContext = Dispatchers.IO,
) : SummarySource {

    private val queries get() = db.ledgerQueries

    /** Validates and saves a new transaction; returns its id. */
    suspend fun create(input: TransactionInput): Long = withContext(io) {
        db.transactionWithResult {
            val valid = validated(input)
            val now = Timestamps.now(clock)
            queries.insertTransaction(
                valid.type.name, valid.amountMinor, valid.date.toString(), valid.categoryId!!, valid.note, now, now,
            )
            queries.lastInsertId().executeAsOne()
        }
    }

    /** Replaces every field; refreshes `updated_at` and keeps `created_at`. */
    suspend fun update(id: Long, input: TransactionInput) = withContext(io) {
        db.transaction {
            val valid = validated(input)
            queries.updateTransaction(
                type = valid.type.name,
                amountMinor = valid.amountMinor,
                entryDate = valid.date.toString(),
                categoryId = valid.categoryId!!,
                note = valid.note,
                updatedAt = Timestamps.now(clock),
                id = id,
            )
            if (queries.changes().executeAsOne() == 0L) throw NoSuchElementException("Transaction $id does not exist")
        }
    }

    suspend fun delete(id: Long) = withContext(io) {
        queries.deleteTransaction(id)
        Unit
    }

    suspend fun get(id: Long): Transaction? = withContext(io) {
        queries.selectTransactionById(id, ::toTransaction).executeAsOneOrNull()
    }

    /** The month's transactions, newest first; same day: most recently created first. */
    fun observeMonth(month: YearMonth, type: TransactionType? = null): Flow<List<Transaction>> {
        val range = DateRange.of(month)
        return queries.selectInRange(range.from, range.to, type?.name, ::toTransaction).asFlow().mapToList(io)
    }

    override fun observeSummary(month: YearMonth): Flow<Summary> = observeSummary(DateRange.of(month))

    override fun observeSummary(year: Year): Flow<Summary> = observeSummary(DateRange.ofYear(year.value))

    private fun observeSummary(range: DateRange): Flow<Summary> =
        queries.periodBreakdown(range.from, range.to).asFlow()
            .map { breakdownQuery ->
                // One read transaction, so the totals and the breakdown come from the same snapshot.
                db.transactionWithResult {
                    val totals = queries.periodTotals(range.from, range.to).executeAsOne()
                    val breakdown = breakdownQuery.executeAsList().map {
                        CategoryTotal(it.category_id, it.category_name, TransactionType.valueOf(it.type), it.total_minor ?: 0)
                    }
                    Summary(totals.income, totals.outcome, breakdown)
                }
            }
            .flowOn(io)

    /** Saves every input in one database transaction, or nothing if any fails. */
    suspend fun importAll(inputs: List<TransactionInput>): Int = withContext(io) {
        require(inputs.size in 1..MAX_IMPORT_ROWS) { "Between 1 and $MAX_IMPORT_ROWS rows can be imported" }
        db.transactionWithResult {
            val now = Timestamps.now(clock)
            inputs.forEachIndexed { index, input ->
                val valid = try {
                    validated(input)
                } catch (e: ValidationException) {
                    throw ValidationException(e.errors.mapValues { "Row ${index + 1}: ${it.value}" })
                }
                queries.insertTransaction(
                    valid.type.name, valid.amountMinor, valid.date.toString(), valid.categoryId!!, valid.note, now, now,
                )
            }
            inputs.size
        }
    }

    /** Ledger transactions per (date, amount, category, type) dated from [first] to [last] inclusive. */
    fun countMatching(first: LocalDate, last: LocalDate): Map<DuplicateKey, Int> =
        queries.countMatching(first.toString(), last.toString()).executeAsList().associate {
            DuplicateKey(LocalDate.parse(it.entry_date), it.amount_minor, it.category_id, TransactionType.valueOf(it.type)) to
                it.matches.toInt()
        }

    private fun validated(input: TransactionInput): TransactionInput =
        when (val result = TransactionValidator.validate(input) { queries.categoryExists(it).executeAsOne() > 0 }) {
            is TransactionValidator.Result.Valid -> result.input
            is TransactionValidator.Result.Invalid -> throw ValidationException(result.errors)
        }

    companion object {
        const val MAX_IMPORT_ROWS = 5000

        @Suppress("LongParameterList")
        private fun toTransaction(
            id: Long, type: String, amountMinor: Long, entryDate: String, categoryId: Long,
            categoryName: String, note: String?, createdAt: String, updatedAt: String,
        ) = Transaction(
            id, TransactionType.valueOf(type), amountMinor, LocalDate.parse(entryDate), categoryId, categoryName,
            note, createdAt, updatedAt,
        )
    }
}
