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
