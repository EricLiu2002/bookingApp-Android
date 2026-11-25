package com.bookingapp.ui.edit

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class EditScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun ComposeTestRule.typeAmount(text: String) {
        onNodeWithTag("amount").performTextClearance()
        onNodeWithTag("amount").performTextInput(text)
    }

    private fun ComposeTestRule.pickCategory(name: String) {
        onNodeWithTag("category").performScrollTo().performClick()
        waitFor(hasText(name))
        onNode(hasText(name) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        waitFor(hasTestTag("category") and hasText(name))
    }

    private fun ComposeTestRule.pickDate(mmddyyyy: String) {
        onNodeWithContentDescription("Choose date").performClick()
        waitFor(isDialog())
        onNodeWithContentDescription("Switch to text input mode", substring = true, ignoreCase = true).performClick()
        val input = hasSetTextAction() and hasAnyAncestor(isDialog())
        onNode(input).performTextClearance()
        onNode(input).performTextInput(mmddyyyy)
        onNode(hasText("OK") and hasAnyAncestor(isDialog())).performClick()
    }

    private fun costsIn(c: com.bookingapp.AppContainer, month: YearMonth) =
        runBlocking { c.transactions.observeMonth(month, TransactionType.COST).first() }

    @Test
    fun addACost() {
        val c = AppTesting.container()
        AppTesting.launch(c).use {
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))

            compose.typeAmount("19.99")
            compose.pickCategory("FO - Groceries")
            compose.onNodeWithTag("save").performScrollTo().performClick()

            compose.waitFor(hasText("Saved $19.99 · FO - Groceries · 2026-10-20"))
            compose.onNodeWithText("Add transaction").assertExists()
            val saved = costsIn(c, YearMonth.of(2026, 10)).single()
            assertEquals(1999L, saved.amountMinor)
            assertEquals(LocalDate.of(2026, 10, 20), saved.date)
            assertEquals("FO - Groceries", saved.categoryName)

            // "View in list" opens that month's list of that type.
            compose.onNodeWithText("View in list").performScrollTo().performClick()
            compose.waitFor(hasText("October 2026"))
            compose.waitFor(hasText("$19.99"))
        }
    }

    @Test
    fun readyForTheNextEntry() {
        val c = AppTesting.container()
        AppTesting.launch(c).use {
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))
            compose.pickDate("10052026")
            compose.onNodeWithTag("date").assertTextContains("2026-10-05")

            compose.typeAmount("12")
            compose.pickCategory("FO - Groceries")
            compose.onNodeWithTag("save").performScrollTo().performClick()
            compose.waitFor(hasText("Saved $12.00 · FO - Groceries · 2026-10-05"))

            // Amount, category and note cleared; date and type kept; focus in the amount.
            compose.onNodeWithTag("amount").assertTextContains("", substring = false).assertIsFocused()
            compose.onNodeWithTag("date").assertTextContains("2026-10-05")

            compose.typeAmount("5")
            compose.waitFor(hasText("Saved", substring = true), count = 0) // editing hides the confirmation
            compose.pickCategory("EN - Books")
            compose.onNodeWithTag("save").performScrollTo().performClick()
            compose.waitFor(hasText("Saved $5.00 · EN - Books · 2026-10-05"))

            val dates = costsIn(c, YearMonth.of(2026, 10)).map { it.date }
            assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)), dates)
        }
    }

    @Test
    fun invalidAmountsBlocked() {
        val c = AppTesting.container()
        AppTesting.launch(c).use {
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))
            compose.pickCategory("FO - Groceries")
            mapOf(
                "0" to "Enter an amount greater than 0",
                "-3" to "Enter a positive number, e.g. 19.99",
                "1.999" to "Use at most two decimal places",
                "abc" to "Enter a positive number, e.g. 19.99",
            ).forEach { (typed, message) ->
                compose.typeAmount(typed)
                compose.onNodeWithTag("save").performScrollTo().performClick()
                compose.waitFor(hasText(message))
            }
            assertTrue(costsIn(c, YearMonth.of(2026, 10)).isEmpty())
        }
    }

    @Test
    fun categoryRequired() {
        val c = AppTesting.container()
        AppTesting.launch(c).use {
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))
            compose.typeAmount("5")
            compose.onNodeWithTag("save").performScrollTo().performClick()

            compose.waitFor(hasText("Choose a category"))
            assertTrue(costsIn(c, YearMonth.of(2026, 10)).isEmpty())
        }
    }

    @Test
    fun noteTooLong() {
        val c = AppTesting.container()
        AppTesting.launch(c).use {
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))
            compose.typeAmount("5")
            compose.pickCategory("FO - Groceries")
            compose.onNodeWithTag("note").performScrollTo().performTextInput("x".repeat(501))
            compose.onNodeWithTag("save").performScrollTo().performClick()

            compose.waitFor(hasText("Note must be at most 500 characters"))
            assertTrue(costsIn(c, YearMonth.of(2026, 10)).isEmpty())
        }
    }

    @Test
    fun editExisting() {
        val c = AppTesting.container()
        val id = c.add(1999, "2026-10-05", note = "Weekly shop")
        AppTesting.launch(c).use {
            compose.clickTab("Transactions")
            compose.waitFor(hasText("$19.99"))
            compose.onNodeWithTag("row-$id").performClick()

            compose.waitFor(hasText("Edit transaction"))
            compose.onNodeWithTag("amount").assertTextContains("19.99")
            compose.typeAmount("20.99")
            compose.onNodeWithTag("save").performScrollTo().performClick()

            compose.waitFor(hasText("$20.99"))
            compose.waitFor(hasText("October 2026"))
            val rows = costsIn(c, YearMonth.of(2026, 10))
            assertEquals(1, rows.size)
            assertEquals(id, rows.single().id)
            assertEquals(2099L, rows.single().amountMinor)
            assertEquals("Weekly shop", rows.single().note)
        }
    }

    @Test
    fun typedInputSurvivesRotation() {
        AppTesting.launch().use { scenario ->
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))
            compose.typeAmount("42.5")
            compose.pickCategory("TR - Gas")

            scenario.recreate()

            compose.waitFor(hasText("Add transaction"))
            compose.onNodeWithTag("amount").assertTextContains("42.5")
            compose.waitFor(hasTestTag("category") and hasText("TR - Gas"))
        }
    }
}
