package com.bookingapp.domain.importing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns a chosen CSV file into text (port of the web app's `decodeCsv`). Files are UTF-8
 * (a byte-order mark is dropped) or, when they are not valid UTF-8, GB18030 — what Excel
 * writes for "CSV" on Chinese Windows.
 */
object CsvDecoder {

    /** 5,000 rows are far below this; anything bigger is almost certainly the wrong file. */
    const val MAX_IMPORT_BYTES: Long = 2L * 1024 * 1024

    const val TOO_LARGE_MESSAGE = "The file is larger than 2 MB. Split it into smaller files and import each one."

    enum class Encoding(val charsetName: String) { UTF_8("UTF-8"), GB18030("GB18030") }

    data class Decoded(val text: String, val encoding: Encoding)

    fun decode(bytes: ByteArray): Decoded = try {
        val text = strict(Encoding.UTF_8).decode(ByteBuffer.wrap(bytes)).toString()
        Decoded(text.removePrefix(CsvReader.BOM), Encoding.UTF_8)
    } catch (e: CharacterCodingException) {
        val decoder = Charset.forName(Encoding.GB18030.charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        Decoded(decoder.decode(ByteBuffer.wrap(bytes)).toString(), Encoding.GB18030)
    }

    private fun strict(encoding: Encoding) = Charset.forName(encoding.charsetName).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
}
