package com.bookingapp.domain

import java.math.BigInteger
import java.text.Collator
import java.util.Locale

/** Pie chart helpers shared by the monthly and yearly Dashboard charts (port of `chart.ts`). */
object ChartGroups {

    /** ARGB colours; the same 12 as the web app's `SLICE_COLORS`. */
    val SLICE_COLORS: List<Long> = listOf(
        0xFF2563EB, 0xFF16A34A, 0xFFF59E0B, 0xFFDC2626, 0xFF7C3AED, 0xFF0891B2,
        0xFFDB2777, 0xFF65A30D, 0xFFEA580C, 0xFF475569, 0xFFCA8A04, 0xFF0D9488,
    )

    data class CategoryAmount(val id: Long, val name: String, val value: Long)

    /** Categories sharing a name prefix, e.g. "EN" for "EN - Sports" and "EN - Lottery". */
    data class Group(val key: String, val label: String, val value: Long, val categories: List<CategoryAmount>)

    /** Like JavaScript's `localeCompare`: ignores case differences before comparing them. */
    private val collator: Collator = Collator.getInstance(Locale.US)

    private fun <T> byAmountThen(name: (T) -> String, value: (T) -> Long): Comparator<T> =
        compareByDescending(value).thenComparator { a, b -> collator.compare(name(a), name(b)) }

    /** The text before the first " - ", or the whole name when there is none. */
    fun groupKeyOf(name: String): String = name.substringBefore(" - ")

    /**
     * Combines one type's category totals into groups by name prefix. Groups are ordered by
     * amount (largest first, then key); their categories by amount, then name.
     */
    fun groupByPrefix(byCategory: List<CategoryTotal>, type: TransactionType): List<Group> {
        val groups = LinkedHashMap<String, MutableList<CategoryAmount>>()
        for (total in byCategory) {
            if (total.type != type) continue
            groups.getOrPut(groupKeyOf(total.categoryName)) { ArrayList() } +=
                CategoryAmount(total.categoryId, total.categoryName, total.totalMinor)
        }
        return groups.map { (key, categories) ->
            Group(
                key = key,
                label = key,
                value = categories.fold(0L) { sum, c -> Math.addExact(sum, c.value) },
                categories = categories.sortedWith(byAmountThen({ it.name }, { it.value })),
            )
        }.sortedWith(byAmountThen({ it.key }, { it.value }))
    }

    /**
     * One group per category, for charts that aren't grouped by prefix (earnings). Keyed by
     * category id and labelled with the full name; ordered by amount, then name.
     */
    fun oneGroupPerCategory(byCategory: List<CategoryTotal>, type: TransactionType): List<Group> =
        byCategory.filter { it.type == type }
            .map { total ->
                val category = CategoryAmount(total.categoryId, total.categoryName, total.totalMinor)
                Group(total.categoryId.toString(), total.categoryName, total.totalMinor, listOf(category))
            }
            .sortedWith(byAmountThen({ it.label }, { it.value }))

    /** Costs are grouped by prefix; earnings are one slice per category. */
    fun groupsFor(byCategory: List<CategoryTotal>, type: TransactionType): List<Group> = when (type) {
        TransactionType.COST -> groupByPrefix(byCategory, type)
        TransactionType.EARNING -> oneGroupPerCategory(byCategory, type)
    }

    /**
     * Assigns colours by the order of the yearly groups (largest first), so a group keeps the
     * same colour in both charts and while stepping through the months of a year.
     */
    fun colorsByGroup(yearGroups: List<Group>): Map<String, Long> = colorsByKey(yearGroups.map { it.key })

    fun colorsByKey(keys: List<String>): Map<String, Long> =
        keys.withIndex().associate { (index, key) -> key to SLICE_COLORS[index % SLICE_COLORS.size] }

    /** True when the slice is under 5% of the total; such slices get no label on the pie. */
    fun isSmallShare(value: Long, total: Long): Boolean =
        BigInteger.valueOf(value).multiply(BigInteger.valueOf(20)) < BigInteger.valueOf(total)
}
