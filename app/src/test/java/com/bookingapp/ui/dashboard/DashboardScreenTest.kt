package com.bookingapp.ui.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.waitFor
import com.bookingapp.ui.common.LocalCurrency
import com.bookingapp.ui.common.UiState
import com.bookingapp.ui.theme.BookingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun inPanel(panel: String, text: String, substring: Boolean = false) =
        hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("panel-$panel"))

    private fun pieLabels(panel: String, labels: String) = hasContentDescription("Pie chart: $labels") and hasTestTag("pie-$panel")

    @Test
    fun bothPeriodsShown() {
        val c = AppTesting.container()
        c.add(5000, "2026-10-05", "FO - Groceries")
        c.add(15000, "2026-03-05", "FO - Groceries")
        c.add(12000, "2026-02-05", "HO - Phone & Internet")
        AppTesting.launch(c).use {
            compose.waitFor(pieLabels("month", "FO 100%"))
            compose.waitFor(inPanel("month", "$50.00"), count = 3) // outcome, the FO slice and its category
            compose.waitFor(inPanel("month", "FO"))

            compose.waitFor(pieLabels("year", "FO 63%, HO 38%"))
            compose.waitFor(inPanel("year", "$200.00"), count = 2) // the FO slice and its category
            compose.waitFor(inPanel("year", "$120.00"), count = 2)
            compose.waitFor(inPanel("year", "63%"), count = 2)
            compose.waitFor(inPanel("year", "38%"), count = 2)
            compose.waitFor(inPanel("year", "FO - Groceries"))
        }
    }

    @Test
    fun steppingIntoAnotherYear() {
        val c = AppTesting.container()
        c.add(999, "2025-06-01", "EN - Books")
        c.add(5000, "2026-01-10", "FO - Groceries")
        AppTesting.launch(c).use {
            compose.waitFor(hasText("October 2026"), count = 2)
            compose.onNodeWithTag("month-label").performClick()
            compose.onNodeWithText("Jan").performClick()
            compose.waitFor(inPanel("month", "January 2026"))

            compose.onNodeWithContentDescription("Previous month").performClick()

            compose.waitFor(inPanel("month", "December 2025"))
            compose.waitFor(inPanel("year", "2025"))
            compose.waitFor(inPanel("year", "$9.99"), count = 3) // outcome, the EN slice and its category
            compose.waitFor(inPanel("month", "No costs recorded this month"))
        }
    }

    @Test
    fun earningsSwitchKeepsTotals() {
        val c = AppTesting.container()
        c.add(350000, "2026-10-01", "IN - Sa", TransactionType.EARNING)
        c.add(5000, "2026-10-02", "IN - Bo", TransactionType.EARNING)
        c.add(1999, "2026-10-03", "FO - Groceries")
        AppTesting.launch(c).use {
            compose.waitFor(pieLabels("month", "FO 100%"))
            compose.waitFor(inPanel("month", "$3,550.00"))
            compose.waitFor(inPanel("month", "$19.99"), count = 3) // outcome, the FO slice and its category
            compose.waitFor(inPanel("month", "$3,530.01"))

            compose.onNodeWithText("Earnings").performClick()

            // One slice per earning category; IN - Bo (1%) is under 5%, so it has no pie label.
            compose.waitFor(pieLabels("month", "IN - Sa 99%"))
            compose.waitFor(pieLabels("year", "IN - Sa 99%"))
            compose.waitFor(inPanel("month", "IN - Sa"))
            compose.waitFor(inPanel("month", "IN - Bo"))
            compose.waitFor(inPanel("month", "$3,500.00"))
            compose.waitFor(inPanel("month", "$50.00"))
            compose.waitFor(inPanel("month", "1%"))
            // Income, Outcome and Balance do not depend on the chart type.
            compose.waitFor(inPanel("month", "$3,550.00"))
            compose.waitFor(inPanel("month", "$19.99"))
            compose.waitFor(inPanel("month", "$3,530.01"))
        }
    }

    @Test
    fun emptyMonthInAYearWithData() {
        val c = AppTesting.container()
        c.add(5000, "2026-03-05", "FO - Groceries")
        AppTesting.launch(c).use {
            compose.waitFor(inPanel("month", "No costs recorded this month"))
            compose.waitFor(pieLabels("year", "FO 100%"))
            compose.onNodeWithText("Earnings").performClick()
            compose.waitFor(inPanel("month", "No earnings recorded this month"))
            compose.waitFor(inPanel("year", "No earnings recorded this year"))
        }
    }

    @Test
    fun tinyShareHasNoPieLabel() {
        val c = AppTesting.container()
        c.add(99999, "2026-10-05", "FO - Groceries")
        c.add(1, "2026-10-05", "HO - Plants")
        AppTesting.launch(c).use {
            compose.waitFor(pieLabels("month", "FO 100%"))
            compose.waitFor(inPanel("month", "<1%"), count = 2) // the HO group and its category
            compose.waitFor(inPanel("month", "100%"), count = 2)
        }
    }

    @Test
    fun negativeBalanceIsMarked() {
        val c = AppTesting.container()
        c.add(1500, "2026-10-05", "FO - Groceries")
        AppTesting.launch(c).use {
            compose.waitFor(inPanel("month", "-$15.00") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "negative"))
        }
    }

    @Test
    fun changeMadeElsewhereUpdatesTheDashboard() {
        val c = AppTesting.container()
        c.add(5000, "2026-10-05", "FO - Groceries")
        AppTesting.launch(c).use {
            compose.waitFor(inPanel("month", "$50.00"), count = 3)
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))

            c.add(2000, "2026-10-06", "FO - Groceries")
            compose.clickTab("Dashboard")

            compose.waitFor(inPanel("month", "$70.00"), count = 3)
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun panelsSideBySideOnWideScreens() {
        AppTesting.launch().use {
            compose.waitFor(hasTestTag("panel-year"))
            val month = compose.onNodeWithTag("panel-month").getBoundsInRoot()
            val year = compose.onNodeWithTag("panel-year").getBoundsInRoot()
            assertEquals(month.top, year.top)
            assertTrue(year.left > month.left)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h2400dp-port")
    fun panelsStackOnPhones() {
        AppTesting.launch().use {
            compose.waitFor(hasTestTag("panel-year"))
            val month = compose.onNodeWithTag("panel-month").getBoundsInRoot()
            val year = compose.onNodeWithTag("panel-year").getBoundsInRoot()
            assertTrue(year.top > month.top)
        }
    }
}

/** Screen-level states that don't need a ledger. */
@RunWith(AndroidJUnit4::class)
class DashboardStatesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readFailureShowsErrorAndRetry() {
        var retried = 0
        compose.setContent {
            CompositionLocalProvider(LocalCurrency provides "USD") {
                BookingTheme {
                    DashboardScreen(YearMonth.of(2026, 10), TransactionType.COST, UiState.Error("The dashboard could not be loaded."), {}, {}, { retried++ })
                }
            }
        }
        compose.onNodeWithText("The dashboard could not be loaded.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun loadingShowsIndicator() {
        compose.setContent {
            BookingTheme { DashboardScreen(YearMonth.of(2026, 10), TransactionType.COST, UiState.Loading, {}, {}, {}) }
        }
        compose.onNodeWithTag("loading").assertExists()
    }
}
