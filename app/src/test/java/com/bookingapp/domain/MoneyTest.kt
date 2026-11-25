package com.bookingapp.domain

import com.bookingapp.domain.Money.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports reference/frontend/money.test.ts, plus the Android form's decimal-comma rule. */
class MoneyTest {

    @Test
    fun parsesExactly() {
        listOf(
            "19.99" to 1999L,
            "3500" to 350000L,
            "0.1" to 10L,
            "0.10" to 10L,
            "0.01" to 1L,
            "  7.5 " to 750L,
            "90071992547409.91" to 9_007_199_254_740_991L,
        ).forEach { (input, expected) ->
            assertEquals(input, ParseResult.Ok(expected), Money.parseMajorToMinor(input))
        }
    }

    @Test
    fun rejectsInvalid() {
        listOf("0", "0.00", "-1", "1.999", "abc", "", "1,000", "1e3", ".5", "90071992547409.92").forEach {
            assertTrue(it, Money.parseMajorToMinor(it) is ParseResult.Error)
        }
    }

    @Test
    fun explainsTooManyDecimalPlaces() {
        assertEquals(ParseResult.Error("Use at most two decimal places"), Money.parseMajorToMinor("1.999"))
    }

    @Test
    fun formAcceptsSingleDecimalComma() {
        assertEquals(ParseResult.Ok(1999), Money.parseFormAmount("19,99"))
        assertEquals(ParseResult.Ok(1999), Money.parseFormAmount("19.99"))
        // Two commas, or a comma together with a dot, are not a decimal comma.
        assertTrue(Money.parseFormAmount("1,000,00") is ParseResult.Error)
        assertTrue(Money.parseFormAmount("1,000.00") is ParseResult.Error)
        assertEquals(ParseResult.Error("Use at most two decimal places"), Money.parseFormAmount("1,999"))
    }

    @Test
    fun formatsAmounts() {
        assertEquals("$19.99", Money.format(1999, "USD"))
        assertEquals("-$15.00", Money.format(-1500, "USD"))
        assertEquals("$0.00", Money.format(0, "USD"))
        assertEquals("$0.10", Money.format(10, "USD"))
        assertEquals("$1,234,567.89", Money.format(123456789, "USD"))
        assertEquals("$90,071,992,547,409.91", Money.format(9_007_199_254_740_991, "USD"))
    }

    @Test
    fun minorToMajorInput() {
        assertEquals("19.99", Money.minorToMajorInput(1999))
        assertEquals("0.05", Money.minorToMajorInput(5))
        assertEquals("3500.00", Money.minorToMajorInput(350000))
    }

    @Test
    fun formatsShares() {
        listOf(
            Triple(5000L, 20000L, "25%"),
            Triple(20000L, 32000L, "63%"),
            Triple(12000L, 32000L, "38%"),
            Triple(1L, 100000L, "<1%"),
            Triple(99999L, 100000L, "100%"),
            Triple(32000L, 32000L, "100%"),
            Triple(Money.MAX_AMOUNT_MINOR, Money.MAX_AMOUNT_MINOR, "100%"),
        ).forEach { (value, total, expected) -> assertEquals(expected, Money.formatShare(value, total)) }
    }
}
