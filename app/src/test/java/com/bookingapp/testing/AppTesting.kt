package com.bookingapp.testing

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.bookingapp.AppContainer
import com.bookingapp.BookingApplication
import com.bookingapp.MainActivity
import com.bookingapp.data.reload.LedgerReplacer
import com.bookingapp.domain.TransactionInput
import com.bookingapp.domain.TransactionType
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate

/** Helpers for Robolectric tests that run the real app against a test ledger. */
object AppTesting {

    /** "Today" in tests: 2026-10-20 (UTC), so the current month is October 2026. */
    val NOW: Instant = Instant.parse("2026-10-20T12:00:00Z")

    val app: BookingApplication get() = ApplicationProvider.getApplicationContext()

    fun container(hook: (LedgerReplacer.Step) -> Unit = {}): AppContainer =
        AppContainer(app, clock = TickingClock(NOW), currency = "USD", replacerHook = hook)

    /** Installs [container] and launches the app on the Dashboard. */
    fun launch(container: AppContainer = container()): ActivityScenario<MainActivity> {
        app.container = container
        return ActivityScenario.launch(MainActivity::class.java)
    }

    fun AppContainer.categoryId(name: String): Long =
        runBlocking { categories.getAll().first { it.name == name }.id }

    fun AppContainer.add(amount: Long, date: String, category: String = "FO - Groceries", type: TransactionType = TransactionType.COST, note: String? = null): Long =
        runBlocking { transactions.create(TransactionInput(type, amount, LocalDate.parse(date), categoryId(category), note)) }
}

/** Waits (driving the main looper) until exactly [count] nodes match. */
fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, count: Int = 1, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == count }
}

/** Taps a bottom navigation item by its label. */
fun ComposeTestRule.clickTab(label: String) {
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
}

fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

/** The system Back action. */
fun ActivityScenario<MainActivity>.pressBack() {
    onActivity { it.onBackPressedDispatcher.onBackPressed() }
}

/** Runs the Robolectric main looper until [condition] holds, for tests without a composition. */
fun waitOnMain(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMillis ms" }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(10)
    }
}
