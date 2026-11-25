package com.bookingapp.domain.importing

import com.bookingapp.domain.importing.CsvDecoder.Encoding
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports reference/frontend/decodeCsv.test.ts. */
class CsvDecoderTest {

    /** "2026/9/5,124.01,食-食材" encoded as GB18030 (as Excel saves CSV on Chinese Windows). */
    private val gb18030Row = intArrayOf(
        0x32, 0x30, 0x32, 0x36, 0x2f, 0x39, 0x2f, 0x35, 0x2c, 0x31, 0x32, 0x34, 0x2e, 0x30, 0x31, 0x2c, 0xca, 0xb3, 0x2d,
        0xca, 0xb3, 0xb2, 0xc4,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun readsUtf8WithoutBom() {
        assertEquals(
            CsvDecoder.Decoded("2026/9/4,$27.25,住-日杂", Encoding.UTF_8),
            CsvDecoder.decode("2026/9/4,$27.25,住-日杂".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun dropsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "食-EO".toByteArray(Charsets.UTF_8)
        assertEquals(CsvDecoder.Decoded("食-EO", Encoding.UTF_8), CsvDecoder.decode(bytes))
    }

    @Test
    fun fallsBackToGb18030() {
        assertEquals(CsvDecoder.Decoded("2026/9/5,124.01,食-食材", Encoding.GB18030), CsvDecoder.decode(gb18030Row))
    }

    @Test
    fun gb18030RowMapsToGroceries() {
        val row = ImportRowParser.parse(
            CsvReader.read(CsvDecoder.decode(gb18030Row).text),
            listOf(com.bookingapp.domain.Category(5, "FO - Groceries")),
        ).single()
        assertEquals("FO - Groceries", row.category?.name)
    }

    @Test
    fun javaGb18030EncoderRoundTrips() {
        val bytes = "2026/9/5,124.01,食-食材".toByteArray(charset("GB18030"))
        assertEquals(gb18030Row.toList(), bytes.toList())
    }

    @Test
    fun sizeLimitIsTwoMegabytes() {
        assertEquals(2L * 1024 * 1024, CsvDecoder.MAX_IMPORT_BYTES)
    }
}
