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
