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
