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
