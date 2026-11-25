package com.bookingapp.ui.common

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.waitFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthSelectorTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun monthShown(label: String) = compose.waitFor(hasText(label))

    @Test
    fun defaultsToCurrentMonthStepsBackAndJumpsWithThePicker() {
        AppTesting.launch().use {
            compose.clickTab("Transactions")
            monthShown("October 2026")

            compose.onNodeWithContentDescription("Previous month").performClick()
            monthShown("September 2026")
            compose.onNodeWithContentDescription("Next month").performClick()
            monthShown("October 2026")

            compose.onNodeWithTag("month-label").performClick()
            // The picker opens on the selected month's year.
            compose.onNodeWithTag("picker-year", useUnmergedTree = true).assertTextEquals("2026")
            compose.onNodeWithContentDescription("Previous year").performClick()
            compose.onNodeWithTag("picker-year", useUnmergedTree = true).assertTextEquals("2025")
            compose.onNodeWithText("Mar").performClick()

            monthShown("March 2025")
        }
    }

    @Test
    fun selectedMonthSurvivesRecreation() {
        AppTesting.launch().use { scenario ->
            compose.clickTab("Transactions")
            monthShown("October 2026")
            compose.onNodeWithTag("month-label").performClick()
            compose.onNodeWithContentDescription("Previous year").performClick()
            compose.onNodeWithText("Mar").performClick()
            monthShown("March 2025")

            scenario.recreate()

            monthShown("March 2025")
        }
    }

    @Test
    fun dashboardMonthSurvivesRecreation() {
        AppTesting.launch().use { scenario ->
            compose.waitFor(hasText("October 2026"), count = 2)
            compose.onNodeWithContentDescription("Previous month").performClick()
            compose.waitFor(hasText("September 2026"), count = 2)

            scenario.recreate()

            compose.waitFor(hasText("September 2026"), count = 2)
        }
    }
}
