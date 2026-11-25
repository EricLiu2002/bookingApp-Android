package com.bookingapp.data

import app.cash.sqldelight.db.QueryResult
import com.bookingapp.testing.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.sql.Connection

class SchemaParityTest {

    private fun backendMaster(connection: Connection): List<Pair<String, String>> =
        connection.createStatement().use { st ->
            st.executeQuery(MASTER_SQL).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to LedgerSchema.normalise(rs.getString(2))) }
            }
        }

    private fun sqldelightMaster(): List<Pair<String, String>> {
        val driver = TestDatabases.driver()
        return driver.executeQuery(null, MASTER_SQL, { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) add(cursor.getString(0)!! to LedgerSchema.normalise(cursor.getString(1)!!))
            })
        }, 0).value
    }

    @Test
    fun sqldelightSchemaMatchesFlywayV1() {
        val backend = TestDatabases.backendDatabase().use(::backendMaster)

        assertEquals(backend, sqldelightMaster())
        // The DDL the reload check expects is the same pinned schema.
        val ledgerObjects = backend.filter { it.first.substringAfter(':') in LedgerSchema.EXPECTED_DDL }
            .associate { it.first.substringAfter(':') to it.second }
        assertEquals(3, ledgerObjects.size)
        assertEquals(ledgerObjects, LedgerSchema.EXPECTED_DDL.mapValues { LedgerSchema.normalise(it.value) })
    }

    @Test
    fun seededCategoriesPresentExactlyOnce() {
        val (_, db) = TestDatabases.ledger()
        val names = db.ledgerQueries.selectAllCategories().executeAsList().map { it.name }
        val seeded = Regex("\\('([^']*)'\\)").findAll(TestDatabases.referenceSql("V2__seed_categories.sql"))
            .map { it.groupValues[1] }.toList()

        assertEquals(32, names.size)
        assertEquals(seeded.sorted(), names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun orphanCategoryIdRejected() {
        val (_, db) = TestDatabases.ledger()

        assertThrows(Exception::class.java) {
            db.ledgerQueries.insertTransaction("COST", 100, "2026-10-01", 9999, null, "t", "t")
        }
        assertEquals(0L, db.ledgerQueries.selectInRange("0000", "9999", null).executeAsList().size.toLong())
    }

    private companion object {
        /** Every schema object, including SQLite's own (sqlite_sequence, the UNIQUE autoindex). */
        const val MASTER_SQL = "SELECT type || ':' || name, COALESCE(sql, '') FROM sqlite_master ORDER BY type, name"
    }
}
