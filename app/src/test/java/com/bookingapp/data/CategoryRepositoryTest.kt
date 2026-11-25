package com.bookingapp.data

import com.bookingapp.testing.TestDatabases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryRepositoryTest {

    @Test
    fun observeAllIsAlphabetical() = runTest {
        val repo = CategoryRepository(TestDatabases.ledger().second, Dispatchers.Unconfined)

        val names = repo.observeAll().first().map { it.name }

        assertEquals(32, names.size)
        assertEquals(names.sorted(), names)
        assertEquals("CL - Apparel", names.first())
        assertEquals("TR - Repairs", names.last())
    }
}
