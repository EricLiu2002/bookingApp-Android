package com.bookingapp.data.reload

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Opens a candidate file read-only with the platform SQLite. */
class AndroidSqlReader(file: File) : SqlReader {

    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun query(sql: String): List<List<String?>> = db.rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(List(cursor.columnCount) { i -> if (cursor.getType(i) == Cursor.FIELD_TYPE_NULL) null else cursor.getString(i) })
            }
        }
    }

    override fun close() = db.close()

    companion object {
        /** Sets `PRAGMA user_version`, keeping the rollback journal (no WAL). */
        fun stampUserVersion(file: File, version: Long) {
            val db = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                db.disableWriteAheadLogging()
                db.execSQL("PRAGMA user_version = $version")
            } finally {
                db.close()
            }
        }
    }
}
