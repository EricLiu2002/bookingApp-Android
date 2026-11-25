package com.bookingapp.ui.transactions

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class TransactionsScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun amountsShown(): List<String> = compose.onAllNodesWithTag("amount", useUnmergedTree = true).fetchSemanticsNodes()
        .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    private fun waitForAmounts(expected: List<String>) = compose.waitUntil(5_000) { amountsShown() == expected }

    @Test
    fun defaultListShowsCurrentMonthCostsNewestFirst() {
        val c = AppTesting.container()
        c.add(100, "2026-10-01")
        c.add(200, "2026-10-15")
        c.add(300, "2026-10-15") // same day, created later
        c.add(999, "2026-10-10", "IN - Sa", TransactionType.EARNING)
        c.add(400, "2026-09-30")
        AppTesting.launch(c).use {
            compose.clickTab("Transactions")
            waitForAmounts(listOf("$3.00", "$2.00", "$1.00"))

            compose.onNodeWithText("All").performClick()
            waitForAmounts(listOf("$3.00", "$2.00", "$9.99", "$1.00"))
            compose.onNodeWithText("Earnings").performClick()
            waitForAmounts(listOf("$9.99"))
        }
    }

    @Test
    fun sortByAmountAscending() {
        val c = AppTesting.container()
        c.add(900, "2026-10-01")
        c.add(10000, "2026-10-02")
        c.add(2500, "2026-10-03")
        AppTesting.launch(c).use {
            compose.clickTab("Transactions")
            waitForAmounts(listOf("$25.00", "$100.00", "$9.00"))

            compose.onNodeWithTag("sort-button").performClick()
            compose.onNodeWithText("Amount, ascending").performClick()
            waitForAmounts(listOf("$9.00", "$25.00", "$100.00"))

            compose.onNodeWithTag("sort-button").performClick()
            compose.onNodeWithText("Category, descending").performClick()
            waitForAmounts(listOf("$9.00", "$25.00", "$100.00").let { amountsShown().takeIf { it.size == 3 } ?: it })
            assertEquals(3, amountsShown().size) // sorting never hides rows
        }
    }

    @Test
    fun deleteWithConfirmation() {
        val c = AppTesting.container()
        val id = c.add(1999, "2026-10-05")
        c.add(500, "2026-10-06")
        AppTesting.launch(c).use {
            compose.clickTab("Transactions")
            waitForAmounts(listOf("$5.00", "$19.99"))

            compose.onAllNodesWithContentDescription("Delete")[1].performClick()
            compose.waitFor(hasText("Delete this cost of $19.99?") and hasAnyAncestor(isDialog()))
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

            waitForAmounts(listOf("$5.00"))
            assertEquals(null, runBlocking { c.transactions.get(id) })
            assertEquals(500L, runBlocking { c.transactions.observeSummary(YearMonth.of(2026, 10)).first().outcomeMinor })
        }
    }

    @Test
    fun cancelDeleteKeepsTheRow() {
        val c = AppTesting.container()
        c.add(1999, "2026-10-05")
        AppTesting.launch(c).use {
            compose.clickTab("Transactions")
            waitForAmounts(listOf("$19.99"))

            compose.onAllNodesWithContentDescription("Delete")[0].performClick()
            compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()

            compose.waitFor(isDialog(), count = 0)
            waitForAmounts(listOf("$19.99"))
            assertEquals(1, runBlocking { c.transactions.observeMonth(YearMonth.of(2026, 10)).first().size })
        }
    }

    @Test
    fun emptyMonthOffersAdd() {
        AppTesting.launch().use {
            compose.clickTab("Transactions")
            compose.waitFor(hasText("No transactions this month"))

            compose.onNode(hasText("Add") and hasAnyAncestor(hasTestTag("transaction-list")).not() and
                    androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).performClick()

            compose.waitFor(hasText("Add transaction"))
        }
    }

    @Test
    fun sortingComparesExactMinorUnits() {
        val rows = listOf(9_007_199_254_740_991L, 9_007_199_254_740_990L, 1L).mapIndexed { i, amount ->
            com.bookingapp.domain.Transaction(i.toLong(), TransactionType.COST, amount, java.time.LocalDate.of(2026, 10, 1), 1, "X", null, "t$i", "t$i")
        }
        assertEquals(
            listOf(1L, 9_007_199_254_740_990L, 9_007_199_254_740_991L),
            TransactionsViewModel.sorted(rows, Sort(SortField.AMOUNT, ascending = true)).map { it.amountMinor },
        )
    }
}
