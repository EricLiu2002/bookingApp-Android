package com.bookingapp.domain.importing

import com.bookingapp.domain.jIsBlank

/**
 * Port of the backend's `CsvReader`. One separator per file (tab if the first non-blank
 * line has a tab outside quotes, otherwise comma), RFC 4180 quoting within a line (`""` is
 * a literal quote), blank lines skipped, a leading BOM ignored, and CRLF / LF / CR line
 * endings. Fields cannot span lines.
 */
object CsvReader {

    /** One non-blank line: its 1-based number in the file and its fields. */
    data class Line(val number: Int, val fields: List<String>)

    private val LINE_BREAK = Regex("\r\n|\n|\r")

    /** U+FEFF, the byte-order mark, written as a code so no BOM character sits in the source. */
    internal val BOM: String = 0xFEFF.toChar().toString()

    fun read(input: String): List<Line> {
        val text = if (input.startsWith(BOM)) input.substring(1) else input
        val physical = text.split(LINE_BREAK)
        val lines = ArrayList<Line>()
        var separator: Char? = null
        for ((i, raw) in physical.withIndex()) {
            if (raw.jIsBlank()) continue
            val sep = separator ?: (if (hasUnquotedTab(raw)) '\t' else ',').also { separator = it }
            lines += Line(i + 1, split(raw, sep))
        }
        return lines
    }

    internal fun split(line: String, separator: Char): List<String> {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else if (c == '"' && field.toString().jIsBlank()) {
                // A quote opens a quoted field only at its start (leading spaces allowed).
                field.setLength(0)
                quoted = true
            } else if (c == separator) {
                fields += field.toString()
                field.setLength(0)
            } else {
                field.append(c)
            }
            i++
        }
        fields += field.toString()
        return fields
    }

    private fun hasUnquotedTab(line: String): Boolean {
        var quoted = false
        for (c in line) {
            if (c == '"') quoted = !quoted
            else if (c == '\t' && !quoted) return true
        }
        return false
    }
}
