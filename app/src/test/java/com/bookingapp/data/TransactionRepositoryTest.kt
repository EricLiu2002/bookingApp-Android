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
