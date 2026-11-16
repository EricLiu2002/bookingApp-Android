package com.bookingapp.data

/**
 * The backend schema this app supports (Flyway V1, seeded by V2). SchemaParityTest pins
 * these statements to both `reference/backend/V1__create_schema.sql` and `Ledger.sq`.
 */
object LedgerSchema {

    /** The newest backend Flyway migration this app can open. */
    const val SUPPORTED_FLYWAY_VERSION = 2

    val EXPECTED_DDL: Map<String, String> = mapOf(
        "category" to """
            CREATE TABLE category (
                id   INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT    NOT NULL UNIQUE
            )
        """,
        "transaction_entry" to """
            CREATE TABLE transaction_entry (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                type         TEXT    NOT NULL CHECK (type IN ('COST', 'EARNING')),
                amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
                entry_date   TEXT    NOT NULL,
                category_id  INTEGER NOT NULL REFERENCES category (id),
                note         TEXT,
                created_at   TEXT    NOT NULL,
                updated_at   TEXT    NOT NULL
            )
        """,
        "ix_transaction_entry_date_created" to
            "CREATE INDEX ix_transaction_entry_date_created ON transaction_entry (entry_date, created_at)",
    )

    private val WHITESPACE = Regex("\\s+")
    private val AROUND_PUNCTUATION = Regex("\\s*([(),])\\s*")

    /** Collapses whitespace so formatting differences don't count as a schema change. */
    fun normalise(sql: String): String =
        sql.trim().replace(WHITESPACE, " ").replace(AROUND_PUNCTUATION, "$1").trimEnd(';').trim()
}
