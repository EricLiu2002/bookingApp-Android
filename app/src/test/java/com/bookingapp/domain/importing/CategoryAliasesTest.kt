package com.bookingapp.domain.importing

import com.bookingapp.domain.Category
import com.bookingapp.testing.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports reference/backend/CategoryAliasesTest.java. */
class CategoryAliasesTest {

    private val seededNames: List<String> =
        Regex("\\('([^']*)'\\)").findAll(TestDatabases.referenceSql("V2__seed_categories.sql")).map { it.groupValues[1] }.toList()

    private val seeded: List<Category> = seededNames.mapIndexed { i, name -> Category(i + 1L, name) }

    @Test
    fun hasAll29Aliases() = assertEquals(29, CategoryAliases.ALIASES.size)

    @Test
    fun everyTargetIsASeededCategory() {
        assertEquals(32, seededNames.size)
        assertTrue(seededNames.containsAll(CategoryAliases.ALIASES.values))
    }

    @Test
    fun aliasesMapOneToOne() {
        assertEquals(CategoryAliases.ALIASES.size, CategoryAliases.ALIASES.values.toSet().size)
    }

    @Test
    fun resolvesChineseName() {
        assertEquals("TR - Gas", CategoryAliases.resolve("行-汽油", seeded)?.name)
        assertEquals("HO - Household Supplies", CategoryAliases.resolve("  住-日杂 ", seeded)?.name)
    }

    @Test
    fun resolvesCurrentNameIgnoringCase() {
        assertEquals("FO - Groceries", CategoryAliases.resolve("fo - groceries", seeded)?.name)
        assertEquals("IN - Sa", CategoryAliases.resolve("IN - Sa", seeded)?.name)
    }

    @Test
    fun unknownNameIsEmpty() {
        assertNull(CategoryAliases.resolve("住-水电", seeded))
        assertNull(CategoryAliases.resolve("", seeded))
        assertNull(CategoryAliases.resolve(null, seeded))
    }
}

###CsvDecoderTest.kt
package com.bookingapp.domain.importing

import com.bookingapp.domain.importing.CsvDecoder.Encoding
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports reference/frontend/decodeCsv.test.ts. */
class CsvDecoderTest {

    /** "2026/9/5,124.01,食-食材" encoded as GB18030 (as Excel saves CSV on Chinese Windows). */
    private val gb18030Row = intArrayOf(
        0x32, 0x30, 0x32, 0x36, 0x2f, 0x39, 0x2f, 0x35, 0x2c, 0x31, 0x32, 0x34, 0x2e, 0x30, 0x31, 0x2c, 0xca, 0xb3, 0x2d,
        0xca, 0xb3, 0xb2, 0xc4,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun readsUtf8WithoutBom() {
        assertEquals(
            CsvDecoder.Decoded("2026/9/4,$27.25,住-日杂", Encoding.UTF_8),
            CsvDecoder.decode("2026/9/4,$27.25,住-日杂".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun dropsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "食-EO".toByteArray(Charsets.UTF_8)
        assertEquals(CsvDecoder.Decoded("食-EO", Encoding.UTF_8), CsvDecoder.decode(bytes))
    }

    @Test
    fun fallsBackToGb18030() {
        assertEquals(CsvDecoder.Decoded("2026/9/5,124.01,食-食材", Encoding.GB18030), CsvDecoder.decode(gb18030Row))
    }

    @Test
    fun gb18030RowMapsToGroceries() {
        val row = ImportRowParser.parse(
            CsvReader.read(CsvDecoder.decode(gb18030Row).text),
            listOf(com.bookingapp.domain.Category(5, "FO - Groceries")),
        ).single()
        assertEquals("FO - Groceries", row.category?.name)
    }

    @Test
    fun javaGb18030EncoderRoundTrips() {
        val bytes = "2026/9/5,124.01,食-食材".toByteArray(charset("GB18030"))
        assertEquals(gb18030Row.toList(), bytes.toList())
    }

    @Test
    fun sizeLimitIsTwoMegabytes() {
        assertEquals(2L * 1024 * 1024, CsvDecoder.MAX_IMPORT_BYTES)
    }
}

###CsvReaderTest.kt
package com.bookingapp.domain.importing

import com.bookingapp.domain.importing.CsvReader.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports every case of reference/backend/CsvReaderTest.java. */
class CsvReaderTest {

    @Test
    fun commaSeparated() {
        assertEquals(
            listOf(Line(1, listOf("2026/9/4", "$27.25", "住-日杂")), Line(2, listOf("2026/9/5", "124.01", "食-食材"))),
            CsvReader.read("2026/9/4,$27.25,住-日杂\n2026/9/5,124.01,食-食材"),
        )
    }

    @Test
    fun tabSeparatedKeepsCommasInsideFields() {
        assertEquals(
            listOf("2026/9/4", "$1,234.56", "住-日杂", "A, B"),
            CsvReader.read("2026/9/4\t$1,234.56\t住-日杂\tA, B").first().fields,
        )
    }

    @Test
    fun quotedFieldMayContainSeparatorAndEscapedQuote() {
        assertEquals(
            listOf("2026/9/4", "7.00", "娱-彩票", "Powerball, \"Mega\""),
            CsvReader.read("2026/9/4,7.00,娱-彩票,\"Powerball, \"\"Mega\"\"\"").first().fields,
        )
    }

    @Test
    fun blankLinesSkippedButLineNumbersKept() {
        assertEquals(listOf(2, 5), CsvReader.read("\n2026/9/4,1,a\n   \n\n2026/9/5,2,b\n").map { it.number })
    }

    @Test
    fun leadingBomIgnored() {
        assertEquals("2026/9/4", CsvReader.read(CsvReader.BOM + "2026/9/4,1,a").first().fields.first())
    }

    @Test
    fun crlfAndCrLineEndings() {
        assertEquals(listOf(1, 2, 3), CsvReader.read("a,1,x\r\nb,2,y\rc,3,z").map { it.number })
    }

    @Test
    fun separatorDetectedFromFirstNonBlankLineOnly() {
        // First line has no tab, so the file is comma-separated even if a later note has a tab.
        assertEquals(listOf("b", "2", "y", "note\twith tab"), CsvReader.read("a,1,x\nb,2,y,note\twith tab")[1].fields)
    }

    @Test
    fun emptyInputHasNoLines() {
        assertTrue(CsvReader.read("").isEmpty())
        assertTrue(CsvReader.read("\n \r\n").isEmpty())
    }
}

###ImportPreviewBuilderTest.kt
package com.bookingapp.domain.importing

import com.bookingapp.data.TransactionRepository
import com.bookingapp.domain.Category
import com.bookingapp.domain.TransactionType
import com.bookingapp.testing.TestDatabases
import com.bookingapp.testing.TickingClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ImportPreviewBuilderTest {

    private val lottery = Category(26, "EN - Lottery")
    private val categories = listOf(lottery, Category(5, "FO - Groceries"))
    private val noLedger: (LocalDate, LocalDate) -> Map<DuplicateKey, Int> = { _, _ -> emptyMap() }

    @Test
    fun rejectsFileWithoutRows() {
        val error = assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build("\n\n", categories, noLedger) }
        assertEquals("The file has no rows to import", error.message)
        // A header alone has no data rows either.
        assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build("Date,Amount,Category", categories, noLedger) }
    }

    @Test
    fun rejectsMoreThan5000Rows() {
        val text = List(5001) { "2026/9/4,1,娱-彩票" }.joinToString("\n")
        val error = assertThrows(ImportFileException::class.java) { ImportPreviewBuilder.build(text, categories, noLedger) }
        assertEquals("The file has 5001 rows; at most 5000 can be imported at once", error.message)
        assertEquals(5000, ImportPreviewBuilder.build(text.substringAfter('\n'), categories, noLedger).rows.size)
    }

    @Test
    fun countsValidInvalidAndDuplicates() {
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        val preview = ImportPreviewBuilder.build("2026/9/4,7,娱-彩票\n2026/9/5,abc,娱-彩票\n2026/9/6,1,食-食材", categories) { first, last ->
            assertEquals(LocalDate.of(2026, 9, 4), first)
            assertEquals(LocalDate.of(2026, 9, 6), last)
            mapOf(key to 1)
        }

        assertEquals(2, preview.validCount)
        assertEquals(1, preview.invalidCount)
        assertEquals(1, preview.duplicateCount)
        assertEquals(listOf(true, false, false), preview.rows.map { it.duplicate })
    }

    @Test
    fun twoIdenticalPurchasesOneAlreadyRecorded() {
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        val preview = ImportPreviewBuilder.build("2026/9/4,7,娱-彩票,a\n2026/9/4,7.00,娱-彩票,b", categories) { _, _ -> mapOf(key to 1) }

        assertEquals(listOf(true, false), preview.rows.map { it.duplicate })
    }

    @Test
    fun invalidRowsAreNeverDuplicates() {
        val rows = ImportRowParser.parse(CsvReader.read("2026/9/4,abc,娱-彩票\n2026/9/4,7,娱-彩票"), categories)
        val key = DuplicateKey(LocalDate.of(2026, 9, 4), 700, lottery.id, TransactionType.COST)
        assertEquals(listOf(false, true), DuplicateFlagger.flag(rows, mapOf(key to 5)))
    }

    @Test
    fun reImportingTheSameFileFlagsEveryRow() = runTest {
        val db = TestDatabases.ledger().second
        val repo = TransactionRepository(db, TickingClock(Instant.parse("2026-10-01T00:00:00Z")), Dispatchers.Unconfined)
        val seeded = db.ledgerQueries.selectAllCategories().executeAsList().map { Category(it.id, it.name) }
        val text = "2026/9/4,7,娱-彩票\n2026/9/4,7,娱-彩票\n2026/9/5,124.01,食-食材,Costco\n2026/9/30,3500,IN - Sa"

        val first = ImportPreviewBuilder.build(text, seeded, repo::countMatching)
        assertEquals(0, first.duplicateCount)
        repo.importAll(first.rows.map { it.toInput()!! })

        val again = ImportPreviewBuilder.build(text, seeded, repo::countMatching)
        assertEquals(4, again.duplicateCount)
        assertEquals(List(4) { true }, again.rows.map { it.duplicate })
    }
}

###ImportRowParserTest.kt
package com.bookingapp.domain.importing

import com.bookingapp.domain.Category
import com.bookingapp.domain.TransactionType
import com.bookingapp.domain.importing.ImportRowParser.ParsedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Ports reference/backend/ImportRowParserTest.java, plus the android-ledger CSV scenarios. */
class ImportRowParserTest {

    private val categories = listOf(
        Category(1, "HO - Household Supplies"), Category(2, "FO - Groceries"),
        Category(3, "EN - Lottery"), Category(4, "IN - Sa"), Category(5, "TR - Gas"),
    )

    private fun parse(csv: String): List<ParsedRow> = ImportRowParser.parse(CsvReader.read(csv), categories)

    private fun one(line: String): ParsedRow = parse(line).first()

    private fun singleError(row: ParsedRow): String = row.errors.single()

    @Test
    fun sampleRow() {
        val row = one("2026/9/4,$27.25,住-日杂")

        assertTrue(row.valid)
        assertEquals(1, row.line)
        assertEquals(LocalDate.of(2026, 9, 4), row.date)
        assertEquals(2725L, row.amountMinor)
        assertEquals("住-日杂", row.sourceCategory)
        assertEquals("HO - Household Supplies", row.category?.name)
        assertEquals(TransactionType.COST, row.type)
        assertNull(row.note)
    }

    @Test
    fun isoDateAndNote() {
        val row = one("2026-09-05\t124.01\t食-食材\t  Costco  ")

        assertEquals(LocalDate.of(2026, 9, 5), row.date)
        assertEquals("Costco", row.note)
    }

    @Test
    fun usMonthDayYearDate() {
        assertEquals(LocalDate.of(2026, 2, 23), one("2/23/2026,1,住-日杂").date)
        assertEquals(LocalDate.of(2026, 2, 3), one("02/03/2026,1,住-日杂").date)
        assertEquals(LocalDate.of(2026, 12, 31), one("12-31-2026,1,住-日杂").date)
        assertTrue(singleError(one("23/2/2026,1,住-日杂")).startsWith("date:"))
        assertTrue(singleError(one("2/30/2026,1,住-日杂")).startsWith("date:"))
    }

    @Test
    fun invalidCalendarDate() {
        val row = one("2026/2/30,1,住-日杂")

        assertFalse(row.valid)
        assertNull(row.date)
        val error = singleError(row)
        assertTrue(error.startsWith("date:") && error.contains("2026/2/30"))
    }

    @Test
    fun currencySymbolAndThousandsSeparator() {
        assertEquals(123456L, one("2026/9/4\t$1,234.56\t住-日杂").amountMinor)
        assertEquals(700L, one("2026/9/4,7,娱-彩票").amountMinor)
        assertEquals(10L, one("2026/9/4,0.1,娱-彩票").amountMinor)
    }

    @Test
    fun badAmounts() {
        singleError(one("2026/9/4,3.999,娱-彩票")).let { assertTrue(it.startsWith("amount:") && it.contains("two decimal places")) }
        singleError(one("2026/9/4,0,娱-彩票")).let { assertTrue(it.startsWith("amount:") && it.contains("greater than 0")) }
        assertTrue(singleError(one("2026/9/4,-5,娱-彩票")).startsWith("amount:"))
        assertTrue(singleError(one("2026/9/4,abc,娱-彩票")).startsWith("amount:"))
        assertTrue(singleError(one("2026/9/4,99999999999999999,娱-彩票")).contains("too large"))
    }

    @Test
    fun incomeCategoryIsEarning() {
        assertEquals(TransactionType.EARNING, one("2026/9/30,3500,IN - Sa").type)
    }

    @Test
    fun unknownCategoryQuotedInError() {
        val row = one("2026/9/4,1,住-水电")

        assertNull(row.category)
        assertNull(row.type)
        singleError(row).let { assertTrue(it.startsWith("category:") && it.contains("住-水电")) }
    }

    @Test
    fun noteTooLong() {
        assertTrue(singleError(one("2026/9/4,1,住-日杂," + "x".repeat(501))).startsWith("note:"))
        assertTrue(one("2026/9/4,1,住-日杂," + "x".repeat(500)).valid)
    }

    @Test
    fun everyErrorCollected() {
        assertEquals(3, one("2026/13/1,0,住-水电").errors.size)
    }

    @Test
    fun tooFewFields() {
        assertTrue(one("2026/9/4,1").errors.any { it.contains("at least 3 fields") })
    }

    @Test
    fun headerSkipped() {
        val row = parse("日期\t金额\t目录\t备注\n2026-09-05\t124.01\t食-食材\tCostco").single()
        assertEquals(2, row.line)
        assertTrue(row.valid)
    }

    @Test
    fun englishHeaderSkipped() {
        assertEquals(2, parse("Date,Amount,Category\n2026/9/4,1,住-日杂").single().line)
    }

    @Test
    fun firstRowBadOnBothDateAndAmountIsStillData() {
        assertFalse(parse("2026/13/1,0,住-日杂").single().valid)
    }

    @Test
    fun badFirstDataRowIsNotTreatedAsHeader() {
        val rows = parse("2026/2/30,5.00,住-日杂\n2026/9/4,1,住-日杂")
        assertEquals(2, rows.size)
        assertFalse(rows.first().valid)
    }

    // android-ledger scenarios -------------------------------------------------------

    @Test
    fun chineseNameAndUsDate() {
        val row = parse("2/23/2026,\"$1,234.56\",行-汽油,\"Fill up, Costco\"").single()

        assertTrue(row.valid)
        assertEquals(1, row.line)
        assertEquals(LocalDate.of(2026, 2, 23), row.date)
        assertEquals(123456L, row.amountMinor)
        assertEquals("TR - Gas", row.category?.name)
        assertEquals(TransactionType.COST, row.type)
        assertEquals("Fill up, Costco", row.note)
    }

    @Test
    fun tabSeparatedWithHeader() {
        val row = parse("日期\t金额\t目录\t备注\n2026-09-30\t3500\tIN - Sa\tSeptember salary").single()

        assertEquals(2, row.line)
        assertEquals(TransactionType.EARNING, row.type)
        assertEquals(350000L, row.amountMinor)
        assertEquals("IN - Sa", row.category?.name)
    }

    @Test
    fun severalErrorsOnDifferentLines() {
        val lines = (1..8).map { n ->
            when (n) {
                3 -> "2026/2/30,1,住-日杂"
                7 -> "2026/9/7,1,住-水电"
                else -> "2026/9/$n,1,住-日杂"
            }
        }
        val rows = parse(lines.joinToString("\n"))

        assertEquals(listOf(3, 7), rows.filterNot { it.valid }.map { it.line })
        assertTrue(rows.first { it.line == 3 }.errors.single().startsWith("date:"))
        assertTrue(rows.first { it.line == 7 }.errors.single().contains("住-水电"))
    }

    @Test
    fun mistypedFirstRowIsReported() {
        val row = parse("2026/13/1,0,住-日杂").single()

        assertEquals(1, row.line)
        assertEquals(listOf("date:", "amount:"), row.errors.map { it.substringBefore(' ') })
    }
}

###../ChartGroupsTest.kt
package com.bookingapp.domain

import com.bookingapp.domain.ChartGroups.CategoryAmount
import com.bookingapp.domain.TransactionType.COST
import com.bookingapp.domain.TransactionType.EARNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports reference/frontend/chart.test.ts and the android-ui chart scenarios. */
class ChartGroupsTest {

    private var nextId = 1L
    private fun total(name: String, minor: Long, type: TransactionType = COST) = CategoryTotal(nextId++, name, type, minor)

    @Test
    fun combinesCategoriesWithTheSamePrefix() {
        val groups = ChartGroups.groupByPrefix(listOf(total("EN - Lottery", 5000), total("EN - Sports", 10000)), COST)

        assertEquals(1, groups.size)
        assertEquals(Triple("EN", "EN", 15000L), Triple(groups[0].key, groups[0].label, groups[0].value))
        assertEquals(listOf("EN - Sports" to 10000L, "EN - Lottery" to 5000L), groups[0].categories.map { it.name to it.value })
    }

    @Test
    fun ordersGroupsByCombinedAmount() {
        val groups = ChartGroups.groupByPrefix(
            listOf(total("FO - Groceries", 10000), total("EN - Sports", 6000), total("EN - Lottery", 5000)), COST,
        )
        assertEquals(listOf("EN" to 11000L, "FO" to 10000L), groups.map { it.key to it.value })
    }

    @Test
    fun ordersEqualGroupsAndEqualCategoriesByName() {
        val groups = ChartGroups.groupByPrefix(
            listOf(total("HO - Plants", 100), total("FO - Groceries", 200), total("HO - Gifts", 100)), COST,
        )
        assertEquals(listOf("FO", "HO"), groups.map { it.key })
        assertEquals(listOf("HO - Gifts", "HO - Plants"), groups[1].categories.map { it.name })
    }

    @Test
    fun keepsCategoryWithoutPrefixAsOwnGroup() {
        val groups = ChartGroups.groupByPrefix(listOf(total("Misc", 300), total("EN - Books", 200)), COST)
        assertEquals(listOf("Misc", "EN"), groups.map { it.label })
    }

    @Test
    fun groupsCostsAndEarningsSeparately() {
        val byCategory = listOf(total("HO - Household Supplies", 800, COST), total("HO - Household Supplies", 200, EARNING))
        assertEquals(listOf(800L), ChartGroups.groupByPrefix(byCategory, COST).map { it.value })
        assertEquals(listOf(200L), ChartGroups.groupByPrefix(byCategory, EARNING).map { it.value })
    }

    @Test
    fun splitsOnlyOnTheFirstSeparator() {
        val groups = ChartGroups.groupByPrefix(listOf(total("HO - Phone - Internet", 100)), COST)
        assertEquals("HO", groups[0].key)
        assertEquals("HO - Phone - Internet", groups[0].categories[0].name)
    }

    @Test
    fun earningsAreOneGroupPerCategory() {
        val sa = total("IN - Sa", 350000, EARNING)
        val bo = total("IN - Bo", 5000, EARNING)

        val groups = ChartGroups.oneGroupPerCategory(listOf(bo, sa, total("FO - Groceries", 100)), EARNING)

        assertEquals(
            listOf(Triple(sa.categoryId.toString(), "IN - Sa", 350000L), Triple(bo.categoryId.toString(), "IN - Bo", 5000L)),
            groups.map { Triple(it.key, it.label, it.value) },
        )
        assertEquals(listOf(CategoryAmount(sa.categoryId, "IN - Sa", 350000)), groups[0].categories)
        val input = listOf(bo, sa)
        assertEquals(ChartGroups.oneGroupPerCategory(input, EARNING), ChartGroups.groupsFor(input, EARNING))
    }

    @Test
    fun coloursFollowYearlyOrder() {
        val colors = ChartGroups.colorsByKey(listOf("HO", "FO", "EN"))
        assertEquals(ChartGroups.SLICE_COLORS[0], colors["HO"])
        assertEquals(ChartGroups.SLICE_COLORS[1], colors["FO"])
        assertEquals(ChartGroups.SLICE_COLORS[2], colors["EN"])
    }

    @Test
    fun monthGroupsGetTheirYearColours() {
        val colors = ChartGroups.colorsByKey(listOf("HO", "FO", "EN"))
        assertEquals(listOf(ChartGroups.SLICE_COLORS[2], ChartGroups.SLICE_COLORS[0]), listOf("EN", "HO").map { colors[it] })
    }

    @Test
    fun firstTwelveGroupsDistinct() {
        assertEquals(12, ChartGroups.colorsByKey(List(12) { "G$it" }).values.toSet().size)
        assertEquals(12, ChartGroups.SLICE_COLORS.size)
    }

    @Test
    fun smallShares() {
        assertTrue(ChartGroups.isSmallShare(4, 100))
        assertFalse(ChartGroups.isSmallShare(5, 100))
        assertTrue(ChartGroups.isSmallShare(1, 100000))
        assertFalse(ChartGroups.isSmallShare(75, 100))
    }

    // android-ui scenarios ------------------------------------------------------------

    @Test
    fun categoriesCombinedIntoEnGroup() {
        val groups = ChartGroups.groupsFor(listOf(total("EN - Sports", 10000), total("EN - Lottery", 5000)), COST)
        assertEquals(listOf("EN" to 15000L), groups.map { it.label to it.value })
        assertEquals(listOf("EN - Sports", "EN - Lottery"), groups[0].categories.map { it.name })
    }

    @Test
    fun percentagesRoundHalfUp() {
        val groups = ChartGroups.groupsFor(listOf(total("FO - Groceries", 20000), total("HO - Phone & Internet", 12000)), COST)
        val sum = groups.sumOf { it.value }
        assertEquals(listOf("63%", "38%"), groups.map { Money.formatShare(it.value, sum) })
    }

    @Test
    fun tinyShare() {
        val groups = ChartGroups.groupsFor(listOf(total("FO - Groceries", 99999), total("HO - Plants", 1)), COST)
        val sum = groups.sumOf { it.value }
        assertEquals(listOf("100%", "<1%"), groups.map { Money.formatShare(it.value, sum) })
        assertEquals(listOf(false, true), groups.map { ChartGroups.isSmallShare(it.value, sum) })
    }
}

###../MoneyTest.kt
package com.bookingapp.domain

import com.bookingapp.domain.Money.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports reference/frontend/money.test.ts, plus the Android form's decimal-comma rule. */
class MoneyTest {

    @Test
    fun parsesExactly() {
        listOf(
            "19.99" to 1999L,
            "3500" to 350000L,
            "0.1" to 10L,
            "0.10" to 10L,
            "0.01" to 1L,
            "  7.5 " to 750L,
            "90071992547409.91" to 9_007_199_254_740_991L,
        ).forEach { (input, expected) ->
            assertEquals(input, ParseResult.Ok(expected), Money.parseMajorToMinor(input))
        }
    }

    @Test
    fun rejectsInvalid() {
        listOf("0", "0.00", "-1", "1.999", "abc", "", "1,000", "1e3", ".5", "90071992547409.92").forEach {
            assertTrue(it, Money.parseMajorToMinor(it) is ParseResult.Error)
        }
    }

    @Test
    fun explainsTooManyDecimalPlaces() {
        assertEquals(ParseResult.Error("Use at most two decimal places"), Money.parseMajorToMinor("1.999"))
    }

    @Test
    fun formAcceptsSingleDecimalComma() {
        assertEquals(ParseResult.Ok(1999), Money.parseFormAmount("19,99"))
        assertEquals(ParseResult.Ok(1999), Money.parseFormAmount("19.99"))
        // Two commas, or a comma together with a dot, are not a decimal comma.
        assertTrue(Money.parseFormAmount("1,000,00") is ParseResult.Error)
        assertTrue(Money.parseFormAmount("1,000.00") is ParseResult.Error)
        assertEquals(ParseResult.Error("Use at most two decimal places"), Money.parseFormAmount("1,999"))
    }

    @Test
    fun formatsAmounts() {
        assertEquals("$19.99", Money.format(1999, "USD"))
        assertEquals("-$15.00", Money.format(-1500, "USD"))
        assertEquals("$0.00", Money.format(0, "USD"))
        assertEquals("$0.10", Money.format(10, "USD"))
        assertEquals("$1,234,567.89", Money.format(123456789, "USD"))
        assertEquals("$90,071,992,547,409.91", Money.format(9_007_199_254_740_991, "USD"))
    }

    @Test
    fun minorToMajorInput() {
        assertEquals("19.99", Money.minorToMajorInput(1999))
        assertEquals("0.05", Money.minorToMajorInput(5))
        assertEquals("3500.00", Money.minorToMajorInput(350000))
    }

    @Test
    fun formatsShares() {
        listOf(
            Triple(5000L, 20000L, "25%"),
            Triple(20000L, 32000L, "63%"),
            Triple(12000L, 32000L, "38%"),
            Triple(1L, 100000L, "<1%"),
            Triple(99999L, 100000L, "100%"),
            Triple(32000L, 32000L, "100%"),
            Triple(Money.MAX_AMOUNT_MINOR, Money.MAX_AMOUNT_MINOR, "100%"),
        ).forEach { (value, total, expected) -> assertEquals(expected, Money.formatShare(value, total)) }
    }
}

###../TransactionValidatorTest.kt
package com.bookingapp.domain

import com.bookingapp.domain.TransactionValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TransactionValidatorTest {

    private val today = LocalDate.of(2026, 10, 5)
    private val exists: (Long) -> Boolean = { it == 1L }

    private fun input(amount: Long = 1999, categoryId: Long? = 1, note: String? = null) =
        TransactionInput(TransactionType.COST, amount, today, categoryId, note)

    private fun errors(result: Result) = (result as Result.Invalid).errors

    @Test
    fun validInputPasses() {
        assertEquals(Result.Valid(input()), TransactionValidator.validate(input(), exists))
    }

    @Test
    fun amountMustBePositiveAndAtMostTheCap() {
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = 0), exists)).keys)
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = -1), exists)).keys)
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = Money.MAX_AMOUNT_MINOR + 1), exists)).keys)
        assertTrue(TransactionValidator.validate(input(amount = Money.MAX_AMOUNT_MINOR), exists) is Result.Valid)
    }

    @Test
    fun categoryRequiredAndMustExist() {
        assertEquals(setOf(Field.CATEGORY), errors(TransactionValidator.validate(input(categoryId = null), exists)).keys)
        assertEquals(setOf(Field.CATEGORY), errors(TransactionValidator.validate(input(categoryId = 2), exists)).keys)
    }

    @Test
    fun noteLimitAndTrimming() {
        assertEquals(setOf(Field.NOTE), errors(TransactionValidator.validate(input(note = "x".repeat(501)), exists)).keys)
        assertTrue(TransactionValidator.validate(input(note = "x".repeat(500)), exists) is Result.Valid)
        assertEquals("Costco", (TransactionValidator.validate(input(note = "  Costco "), exists) as Result.Valid).input.note)
        assertNull((TransactionValidator.validate(input(note = "   "), exists) as Result.Valid).input.note)
        // Trimming happens before the length check.
        assertTrue(TransactionValidator.validate(input(note = " " + "x".repeat(500) + " "), exists) is Result.Valid)
    }

    @Test
    fun formRejectsInvalidAmountText() {
        listOf("0", "-3", "1.999", "abc", "", "90071992547409.92").forEach { text ->
            val result = TransactionValidator.validateForm(TransactionType.COST, text, today, 1, null, exists)
            assertEquals(text, setOf(Field.AMOUNT), errors(result).keys)
        }
    }

    @Test
    fun formCollectsEveryFieldError() {
        val result = TransactionValidator.validateForm(TransactionType.COST, "abc", null, null, "x".repeat(501), exists)
        assertEquals(setOf(Field.AMOUNT, Field.DATE, Field.CATEGORY, Field.NOTE), errors(result).keys)
    }

    @Test
    fun formConvertsExactly() {
        val result = TransactionValidator.validateForm(TransactionType.EARNING, "0.10", today, 1, " ", exists)
        assertEquals(Result.Valid(TransactionInput(TransactionType.EARNING, 10, today, 1, null)), result)
    }
}
