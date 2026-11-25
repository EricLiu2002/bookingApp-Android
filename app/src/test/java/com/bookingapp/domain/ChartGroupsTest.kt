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