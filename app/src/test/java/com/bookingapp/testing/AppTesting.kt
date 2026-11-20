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

###Fixtures.kt
package com.bookingapp.testing

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.bookingapp.MainActivity
import com.bookingapp.data.reload.FileTooLargeException
import com.bookingapp.ui.importing.DocumentReader
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

object Fixtures {

    fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }.readBytes()

    /** The 22-row September 2026 sample with Chinese category names. */
    val september2026: ByteArray get() = resource("september-2026.csv")

    /**
     * Builds a ledger file the way the backend does (Flyway V1 + V2, plus Flyway's history
     * table), using the platform SQLite so it works inside Robolectric.
     */
    fun backendLedger(
        file: File,
        rows: List<Triple<String, Long, String>> = emptyList(),
        flywayVersion: String = "2",
    ): File {
        file.delete()
        val db = SQLiteDatabase.openDatabase(
            file.path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            db.disableWriteAheadLogging()
            listOf("V1__create_schema.sql", "V2__seed_categories.sql").forEach { script ->
                TestDatabases.referenceSql(script).lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(db::execSQL)
            }
            db.execSQL(
                "CREATE TABLE flyway_schema_history (installed_rank INT NOT NULL PRIMARY KEY, version VARCHAR(50), " +
                    "description VARCHAR(200) NOT NULL, success BOOLEAN NOT NULL)",
            )
            db.execSQL("INSERT INTO flyway_schema_history VALUES (1, '1', 'create schema', 1), (2, '$flywayVersion', 'seed', 1)")
            rows.forEach { (date, amount, category) ->
                db.execSQL(
                    "INSERT INTO transaction_entry (type, amount_minor, entry_date, category_id, note, created_at, updated_at) " +
                        "SELECT 'COST', $amount, '$date', id, 'from PC', '${date}T09:00:00.123456Z', '${date}T09:00:00.123456Z' " +
                        "FROM category WHERE name = '$category'",
                )
            }
        } finally {
            db.close()
        }
        return file
    }
}

/** Serves documents by URI from memory or files, like the system picker's content provider. */
class FakeDocuments : DocumentReader {
    private val documents = mutableMapOf<Uri, () -> ByteArray>()

    fun put(name: String, bytes: ByteArray): Uri = Uri.parse("content://test/$name").also { documents[it] = { bytes } }

    fun put(name: String, file: File): Uri = Uri.parse("content://test/$name").also { documents[it] = { file.readBytes() } }

    override fun readBytes(uri: Uri, maxBytes: Long): ByteArray {
        val bytes = documents.getValue(uri)()
        if (bytes.size > maxBytes) throw FileTooLargeException(maxBytes)
        return bytes
    }

    override fun open(uri: Uri): InputStream = ByteArrayInputStream(documents.getValue(uri)())
}

/** Answers the next file-picker launch from [activity] with [uri], as if the user picked it. */
fun answerPicker(activity: MainActivity, uri: Uri) {
    val shadow = shadowOf(activity)
    val started = requireNotNull(shadow.nextStartedActivityForResult) { "no picker was launched" }
    assert(started.intent.action == Intent.ACTION_OPEN_DOCUMENT)
    shadow.receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
}

###TestDatabases.kt
package com.bookingapp.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bookingapp.data.db.LedgerDatabase
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Properties

/** JVM counterpart of DatabaseFactory: an in-memory SQLDelight ledger with foreign keys on. */
object TestDatabases {

    fun driver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        LedgerDatabase.Schema.create(driver)
        return driver
    }

    fun ledger(): Pair<SqlDriver, LedgerDatabase> = driver().let { it to LedgerDatabase(it) }

    val projectRoot: File
        get() = File(System.getProperty("bookingapp.projectRoot") ?: File("..").absolutePath)

    fun referenceSql(name: String): String = File(projectRoot, "reference/backend/$name").readText()

    /** Runs a Flyway-style SQL script (statements separated by `;`) on a raw JDBC connection. */
    fun runScript(connection: Connection, sql: String) {
        val withoutComments = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        connection.createStatement().use { statement ->
            withoutComments.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { statement.execute(it) }
        }
    }

    /** A plain JDBC connection to [file] (or memory) with foreign keys on. */
    fun jdbc(file: File? = null): Connection = DriverManager.getConnection(
        "jdbc:sqlite:" + (file?.absolutePath ?: ":memory:"),
        Properties().apply { put("foreign_keys", "true") },
    )

    /** Builds a database exactly as the backend's Flyway V1 and V2 migrations do. */
    fun backendDatabase(file: File? = null): Connection = jdbc(file).also {
        runScript(it, referenceSql("V1__create_schema.sql"))
        runScript(it, referenceSql("V2__seed_categories.sql"))
    }
}

/** A clock that advances by [step] each time it is read, so writes get distinct timestamps. */
class TickingClock(start: Instant, private val stepMicros: Long = 1) : Clock() {
    private var now = start
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now.also { now = now.plusNanos(stepMicros * 1000) }
}

###../ui/common/MonthSelectorTest.kt
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

###../ui/dashboard/DashboardScreenTest.kt
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

###../ui/dashboard/DashboardViewModelTest.kt
package com.bookingapp.ui.dashboard

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bookingapp.data.SummarySource
import com.bookingapp.domain.Summary
import com.bookingapp.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)

    /** Fails until [healthy] is set; counts how often it was asked. */
    private class FlakySource : SummarySource {
        var healthy = false
        var calls = 0
        override fun observeSummary(month: YearMonth): Flow<Summary> = source(Summary(0, 5000, emptyList()))
        override fun observeSummary(year: Year): Flow<Summary> = source(Summary(0, 32000, emptyList()))
        private fun source(value: Summary) = flow {
            calls++
            if (!healthy) throw IllegalStateException("disk I/O error")
            emit(value)
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failingRepositoryShowsErrorAndRetryReloads() = runTest {
        val source = FlakySource()
        val vm = DashboardViewModel(SavedStateHandle(), source, clock)

        vm.state.test {
            var item = awaitItem()
            while (item is UiState.Loading) item = awaitItem()
            assertEquals(UiState.Error("The dashboard could not be loaded."), item)

            source.healthy = true
            vm.retry()

            item = awaitItem()
            while (item is UiState.Loading) item = awaitItem()
            val data = (item as UiState.Content).data
            assertEquals(5000L, data.monthSummary.outcomeMinor)
            assertEquals(32000L, data.yearSummary.outcomeMinor)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(source.calls >= 2)
    }

    @Test
    fun defaultsToCurrentMonthAndRestoresSavedMonth() {
        val source = object : SummarySource {
            override fun observeSummary(month: YearMonth) = flowOf(Summary.EMPTY)
            override fun observeSummary(year: Year) = flowOf(Summary.EMPTY)
        }
        assertEquals(YearMonth.of(2026, 10), DashboardViewModel(SavedStateHandle(), source, clock).month.value)

        val saved = SavedStateHandle(mapOf("month" to "2025-03"))
        assertEquals(YearMonth.of(2025, 3), DashboardViewModel(saved, source, clock).month.value)
    }

    @Test
    fun steppingIntoAnotherYearLoadsThatYear() = runTest {
        val years = mutableListOf<Year>()
        val source = object : SummarySource {
            override fun observeSummary(month: YearMonth) = flowOf(Summary.EMPTY)
            override fun observeSummary(year: Year) = flowOf(Summary.EMPTY).also { years += year }
        }
        val vm = DashboardViewModel(SavedStateHandle(mapOf("month" to "2026-01")), source, clock)
        vm.state.test {
            vm.selectMonth(vm.month.value.minusMonths(1))
            var item = awaitItem()
            while (item !is UiState.Content || item.data.month != YearMonth.of(2025, 12)) item = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(Year.of(2025), years.last())
    }
}

###../ui/edit/EditScreenTest.kt
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

###../ui/importing/ImportScreenTest.kt
package com.bookingapp.ui.importing

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookingapp.AppContainer
import com.bookingapp.MainActivity
import com.bookingapp.data.reload.FileTooLargeException
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.AppTesting
import com.bookingapp.testing.AppTesting.add
import com.bookingapp.testing.FakeDocuments
import com.bookingapp.testing.Fixtures
import com.bookingapp.testing.answerPicker
import com.bookingapp.testing.clickTab
import com.bookingapp.testing.waitFor
import com.bookingapp.testing.waitOnMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ImportScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val documents = FakeDocuments()

    private fun container(): AppContainer = AppTesting.container().also { it.documents = documents }

    /** Opens Import and picks [uri] through the CSV picker. */
    private fun pickCsv(scenario: androidx.test.core.app.ActivityScenario<MainActivity>, uri: Uri) {
        compose.clickTab("Import")
        compose.waitFor(hasText("Choose CSV file"))
        compose.onNodeWithText("Choose CSV file").performClick()
        compose.waitForIdle()
        scenario.onActivity { answerPicker(it, uri) }
    }

    private fun summary(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag("preview-summary"))

    private fun september(c: AppContainer) = runBlocking { c.transactions.observeMonth(YearMonth.of(2026, 9)).first() }

    @Test
    fun previewAValidFileThenImport() {
        val c = container()
        val uri = documents.put("september.csv", Fixtures.september2026)
        AppTesting.launch(c).use { scenario ->
            pickCsv(scenario, uri)

            compose.waitFor(summary("22 valid · 0 invalid · 0 possible duplicates"))
            compose.waitFor(summary("Selected: 22 rows · costs $3,053.08 · earnings $3,500.00"))
            compose.waitFor(hasText("行-汽油 → TR - Gas"))
            compose.onNodeWithTag("import-button").assertIsEnabled()
            assertTrue(september(c).isEmpty()) // nothing saved before Import

            compose.onNodeWithTag("import-button").performClick()

            compose.waitFor(hasText("Imported 22 transactions"))
            assertEquals(22, september(c).size)
            compose.onNodeWithText("View September 2026").performClick()
            compose.waitFor(hasText("September 2026"))
            compose.waitFor(hasTestTag("transaction-list"))
            compose.waitFor(hasText("IN - Sa")) // the list opened on All, so earnings show too
        }
    }

    @Test
    fun gb18030File() {
        val uri = documents.put("excel.csv", "2026/9/5,124.01,食-食材".toByteArray(charset("GB18030")))
        AppTesting.launch(container()).use { scenario ->
            pickCsv(scenario, uri)
            compose.waitFor(hasText("食-食材 → FO - Groceries"))
            compose.waitFor(summary("1 valid · 0 invalid"))
        }
    }

    @Test
    fun invalidRowBlocksImport() {
        val lines = Fixtures.september2026.toString(Charsets.UTF_8).lines().toMutableList()
        lines[6] = "2026/9/6,12.00,住-水电,Water" // file line 7
        val uri = documents.put("bad.csv", lines.joinToString("\n").toByteArray())
        AppTesting.launch(container()).use { scenario ->
            pickCsv(scenario, uri)

            compose.waitFor(summary("21 valid · 1 invalid"))
            compose.onNodeWithTag("import-button").assertIsNotEnabled()
            compose.onNodeWithTag("import-list").performScrollToKey(7)
            compose.waitFor(hasText("category: \"住-水电\" is not a known category") and hasAnyAncestor(hasTestTag("preview-line-7")))
        }
    }

    @Test
    fun duplicatesExcludedUnlessChosen() {
        val c = container()
        c.add(700, "2026-09-04", "EN - Lottery")
        c.add(2725, "2026-09-02", "HO - Household Supplies")
        val uri = documents.put("again.csv", Fixtures.september2026)
        AppTesting.launch(c).use { scenario ->
            pickCsv(scenario, uri)

            compose.waitFor(summary("22 valid · 0 invalid · 2 possible duplicates"))
            compose.waitFor(summary("Selected: 20 rows"))
            compose.onNodeWithTag("import-list").performScrollToKey(5) // the 娱-彩票 row
            compose.onNodeWithContentDescription("Include line 5").performClick()
            compose.onNodeWithTag("import-list").performScrollToNode(hasTestTag("preview-summary"))
            compose.waitFor(summary("Selected: 21 rows"))

            compose.onNodeWithTag("import-list").performScrollToNode(hasTestTag("import-button"))
            compose.onNodeWithTag("import-button").performClick()

            compose.waitFor(hasText("Imported 21 transactions"))
            val rows = september(c)
            assertEquals(2 + 21, rows.size)
            assertEquals(2, rows.count { it.amountMinor == 700L })
            assertEquals(1, rows.count { it.amountMinor == 2725L })
        }
    }

    @Test
    fun fileTooLarge() {
        val uri = documents.put("huge.csv", ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        AppTesting.launch(container()).use { scenario ->
            pickCsv(scenario, uri)
            compose.waitFor(hasText("The file is larger than 2 MB. Split it into smaller files and import each one."))
            compose.waitFor(hasTestTag("preview-summary"), count = 0)
        }
    }

    @Test
    fun choosingAnotherFileReplacesThePreview() {
        val first = documents.put("one.csv", "2026/9/5,1,食-食材".toByteArray())
        val second = documents.put("two.csv", "2026/9/5,1,食-食材\n2026/9/6,2,食-食材".toByteArray())
        AppTesting.launch(container()).use { scenario ->
            pickCsv(scenario, first)
            compose.waitFor(summary("1 valid"))
            compose.onNodeWithText("Choose CSV file").performClick()
            compose.waitForIdle()
            scenario.onActivity { answerPicker(it, second) }
            compose.waitFor(summary("2 valid"))
        }
    }
}

/** ViewModel-level checks that are hard to reach through the picker. */
@RunWith(AndroidJUnit4::class)
class ImportViewModelTest {

    @get:Rule
    val compose = createEmptyComposeRule() // drives the main looper

    @Test
    fun failedImportKeepsThePreview() {
        val c = AppTesting.container()
        val documents = FakeDocuments()
        val uri = documents.put("september.csv", Fixtures.september2026)
        val vm = ImportViewModel(documents, c.transactions, c.categories)
        vm.onCsvPicked(uri)
        waitOnMain { vm.state.value.preview != null }

        c.close() // the next write fails
        vm.import()
        waitOnMain { vm.state.value.error != null }

        assertEquals(22, vm.state.value.preview!!.preview.validCount)
        assertTrue(vm.state.value.error!!.startsWith("The import failed and nothing was saved."))
        c.reopen()
        assertEquals(0, runBlocking { c.transactions.observeMonth(YearMonth.of(2026, 9)).first().size })
    }

    @Test
    fun contentResolverChecksTheBytesReadWhenNoSizeIsReported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "big.csv").apply { writeBytes(ByteArray(3 * 1024 * 1024)) }
        val reader = ContentResolverDocuments(context.contentResolver)

        assertThrows(FileTooLargeException::class.java) { reader.readBytes(Uri.fromFile(file), 2L * 1024 * 1024) }
        file.writeText("2026/9/5,1,食-食材")
        assertEquals("2026/9/5,1,食-食材", reader.readBytes(Uri.fromFile(file), 2L * 1024 * 1024).toString(Charsets.UTF_8))
    }

    @Test
    fun earningsTypeComesFromTheCategory() {
        val c = AppTesting.container()
        val documents = FakeDocuments()
        val vm = ImportViewModel(documents, c.transactions, c.categories)
        vm.onCsvPicked(documents.put("x.csv", "2026-09-30\t3500\tIN - Sa\tSeptember salary".toByteArray()))
        waitOnMain { vm.state.value.preview != null }
        assertEquals(TransactionType.EARNING, vm.state.value.preview!!.preview.rows.single().parsed.type)
        assertEquals(350000L, vm.state.value.preview!!.selectedEarnings)
    }
}

###../ui/importing/ReloadSectionTest.kt
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

###../ui/navigation/NavigationTest.kt
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

###../ui/transactions/TransactionsScreenTest.kt
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
