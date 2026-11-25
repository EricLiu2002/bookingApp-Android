package com.bookingapp.ui.navigation

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.pressBack
import com.bookingapp.testing.waitFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun opensOnDashboardAndEachTabOpensItsScreen() {
        AppTesting.launch().use {
            compose.waitFor(hasText("October 2026"), count = 2) // month label and monthly panel heading
            compose.waitFor(hasText("Income"), count = 2)

            compose.clickTab("Transactions")
            compose.waitFor(hasText("All"))
            compose.waitFor(hasText("No transactions this month"))

            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))

            compose.clickTab("Import")
            compose.waitFor(hasText("Choose CSV file"))
            compose.waitFor(hasText("Replace ledger with a database file"))

            compose.clickTab("Dashboard")
            compose.waitFor(hasText("Income"), count = 2)
        }
    }

    @Test
    fun backFromImportReturnsToDashboard() {
        AppTesting.launch().use {
            compose.waitFor(hasText("Income"), count = 2)
            compose.clickTab("Import")
            compose.waitFor(hasText("Choose CSV file"))

            it.pressBack()

            compose.waitFor(hasText("Income"), count = 2)
            compose.waitFor(hasText("Choose CSV file"), count = 0)
        }
    }

    @Test
    fun backFromAnyTopLevelScreenReturnsToDashboard() {
        AppTesting.launch().use {
            compose.waitFor(hasText("Income"), count = 2)
            compose.clickTab("Transactions")
            compose.waitFor(hasText("All"))
            compose.clickTab("Add")
            compose.waitFor(hasText("Add transaction"))

            it.pressBack()

            compose.waitFor(hasText("Income"), count = 2)
        }
    }
}
