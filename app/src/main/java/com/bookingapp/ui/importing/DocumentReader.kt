package com.bookingapp.ui.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.bookingapp.data.reload.FileTooLargeException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads documents picked through the Storage Access Framework (design decision 11). */
interface DocumentReader {
    /** All bytes of [uri]; throws [FileTooLargeException] when it is over [maxBytes]. */
    fun readBytes(uri: Uri, maxBytes: Long): ByteArray

    fun open(uri: Uri): InputStream
}

class ContentResolverDocuments(private val resolver: ContentResolver) : DocumentReader {

    override fun readBytes(uri: Uri, maxBytes: Long): ByteArray {
        // Check the reported size first, then the bytes actually read, since a size may be missing.
        reportedSize(uri)?.let { if (it > maxBytes) throw FileTooLargeException(maxBytes) }
        open(uri).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw FileTooLargeException(maxBytes)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    override fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("The file could not be opened")

    private fun reportedSize(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
