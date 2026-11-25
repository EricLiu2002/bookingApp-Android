package com.bookingapp.ui.importing

import android.net.Uri
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.AppContainer
import com.bookingapp.MainActivity
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.FakeDocuments
import com.bookingapp.testing.Fixtures
import com.bookingapp.testing.answerPicker
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.pressBack
import com.bookingapp.testing.waitFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ReloadSectionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val documents = FakeDocuments()

    private fun container(): AppContainer = AppTesting.container().also {
        it.documents = documents
        it.add(4242, "2026-10-05") // phone-only data
    }

    /** 1,250 backend transactions from 2025-01-03 to 2026-11-12, including $7.00 on 2026-10-02. */
    private fun backendUri(): Uri {
        var day = LocalDate.of(2025, 1, 3)
        val rows = (1..1248).map {
            Triple(day.toString(), 100L + it, "FO - Groceries").also { day = if (day.isBefore(LocalDate.of(2026, 9, 30))) day.plusDays(1) else day }
        } + listOf(Triple("2026-10-02", 700L, "EN - Lottery"), Triple("2026-11-12", 500L, "TR - Gas"))
        val file = Fixtures.backendLedger(File(AppTesting.app.filesDir, "bookingapp-pc.db"), rows)
        return documents.put("bookingapp.db", file)
    }

    private fun chooseDatabase(scenario: ActivityScenario<MainActivity>, uri: Uri) {
        compose.clickTab("Import")
        compose.waitFor(hasText("Choose CSV file"))
        compose.onNodeWithTag("import-list").performScrollToNode(hasText("Choose database file"))
        compose.waitFor(hasText("Stop the BookingApp backend before copying its bookingapp.db to the phone, then choose that file here."))
        compose.onNode(hasText("Choose database file")).performClick()
        compose.waitForIdle()
        scenario.onActivity { answerPicker(it, uri) }
    }

    private fun dialogButton(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(isDialog()))

    private fun phoneAmounts(c: AppContainer) = runBlocking { c.transactions.observeMonth(YearMonth.of(2026, 10)).first().map { it.amountMinor } }

    @Test
    fun replaceAfterConfirming() {
        val c = container()
        val uri = backendUri()
        AppTesting.launch(c).use { scenario ->
            chooseDatabase(scenario, uri)

            compose.waitFor(
                hasText("The file has 1,250 transactions from 2025-01-03 to 2026-11-12 and 32 categories.", substring = true) and
                        hasAnyAncestor(isDialog()),
                timeoutMillis = 15_000,
            )
            compose.waitFor(hasText("All transactions on this phone will be replaced", substring = true))
            assertEquals(listOf(4242L), phoneAmounts(c)) // unchanged until confirmed

            dialogButton("Replace").performClick()

            compose.waitFor(hasText("Ledger replaced: 1,250 transactions"), timeoutMillis = 15_000)
            compose.waitFor(hasText("October 2026"), count = 2) // the Dashboard, on the current month
            compose.waitFor(hasText("$7.00") and hasAnyAncestor(hasTestTag("panel-month")), count = 3)
            assertEquals(listOf(700L), phoneAmounts(c))

            // Nothing from before is left on the back stack: Back leaves the app.
            scenario.pressBack()
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun cancelKeepsPhoneData() {
        val c = container()
        val uri = backendUri()
        AppTesting.launch(c).use { scenario ->
            chooseDatabase(scenario, uri)
            compose.waitFor(hasText("Replace") and hasAnyAncestor(isDialog()), timeoutMillis = 15_000)

            dialogButton("Cancel").performClick()

            compose.waitFor(isDialog(), count = 0)
            assertEquals(listOf(4242L), phoneAmounts(c))
            assertFalse(c.ledgerReplacer!!.hasBackup.value)
            compose.waitFor(hasText("Restore previous ledger"), count = 0)
        }
    }

    @Test
    fun rejectedFileIsExplained() {
        val c = container()
        val uri = documents.put("september.csv", Fixtures.september2026)
        AppTesting.launch(c).use { scenario ->
            chooseDatabase(scenario, uri)

            compose.waitFor(hasText("This file is not a BookingApp database.") and hasTestTag("reload-error"))
            compose.waitFor(isDialog(), count = 0)
            assertEquals(listOf(4242L), phoneAmounts(c))
        }
    }

    @Test
    fun newerBackendIsExplained() {
        val c = container()
        val uri = documents.put("v3.db", Fixtures.backendLedger(File(AppTesting.app.filesDir, "v3.db"), flywayVersion = "3"))
        AppTesting.launch(c).use { scenario ->
            chooseDatabase(scenario, uri)
            compose.waitFor(hasText("This database comes from a newer BookingApp backend (migration 3). Update the app first."))
            assertEquals(listOf(4242L), phoneAmounts(c))
        }
    }

    @Test
    fun restoreOfferedAfterAReload() {
        val c = container()
        val uri = backendUri()
        AppTesting.launch(c).use { scenario ->
            chooseDatabase(scenario, uri)
            compose.waitFor(hasText("Replace") and hasAnyAncestor(isDialog()), timeoutMillis = 15_000)
            dialogButton("Replace").performClick()
            compose.waitFor(hasText("Ledger replaced: 1,250 transactions"), timeoutMillis = 15_000)

            compose.clickTab("Import")
            compose.onNodeWithTag("import-list").performScrollToNode(hasText("Restore previous ledger"))
            compose.onNode(hasText("Restore previous ledger")).performClick()
            compose.waitFor(hasText("Restore previous ledger?"))
            dialogButton("Restore").performClick()

            compose.waitFor(hasText("Previous ledger restored"), timeoutMillis = 15_000)
            compose.waitFor(hasText("$42.42") and hasAnyAncestor(hasTestTag("panel-month")), count = 3)
            assertEquals(listOf(4242L), phoneAmounts(c))

            compose.clickTab("Import")
            compose.onNodeWithTag("import-list").performScrollToNode(hasText("Choose database file"))
            compose.waitFor(hasText("Restore previous ledger"), count = 0)
        }
    }
}
