package com.bookingapp.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Money helpers. Amounts are whole minor units held in [Long]; parsing never goes through
 * floating point, so "19.99" always becomes exactly 1999.
 */
object Money {

    /** JavaScript's largest exactly representable integer; also the backend's cap. */
    const val MAX_AMOUNT_MINOR: Long = 9_007_199_254_740_991L

    private val AMOUNT = Regex("^\\d+(\\.\\d{1,2})?$")
    private val TOO_MANY_DECIMALS = Regex("^\\d+\\.\\d{3,}$")
    private val HUNDRED = BigInteger.valueOf(100)
    private val TWO = BigInteger.valueOf(2)
    private val TWO_HUNDRED = BigInteger.valueOf(200)

    sealed interface ParseResult {
        data class Ok(val minor: Long) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    /** Parses major-unit text exactly, with the web form's messages. */
    fun parseMajorToMinor(input: String): ParseResult {
        val value = input.jstrip()
        if (value.isEmpty()) return ParseResult.Error("Enter an amount")
        if (!AMOUNT.matches(value)) {
            return ParseResult.Error(
                if (TOO_MANY_DECIMALS.matches(value)) "Use at most two decimal places"
                else "Enter a positive number, e.g. 19.99",
            )
        }
        val whole = value.substringBefore('.')
        val fraction = value.substringAfter('.', "").padEnd(2, '0')
        val minor = BigInteger(whole).multiply(HUNDRED).add(BigInteger(fraction))
        if (minor.signum() <= 0) return ParseResult.Error("Enter an amount greater than 0")
        if (minor > BigInteger.valueOf(MAX_AMOUNT_MINOR)) return ParseResult.Error("Amount is too large")
        return ParseResult.Ok(minor.toLong())
    }

    /**
     * The Add/Edit form's rule: on keyboards that type `,` as the decimal separator, a single
     * `,` with no `.` is read as the decimal point. Anything else is parsed as typed.
     */
    fun parseFormAmount(input: String): ParseResult {
        val normalised = if (input.count { it == ',' } == 1 && '.' !in input) input.replace(',', '.') else input
        return parseMajorToMinor(normalised)
    }

    /** Formats minor units for display, e.g. 1999 → "$19.99", -1500 → "-$15.00". */
    fun format(minor: Long, currency: String, locale: Locale = Locale.US): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = Currency.getInstance(currency)
        format.minimumFractionDigits = 2
        format.maximumFractionDigits = 2
        // Exact: a BigDecimal with scale 2, never a double.
        return format.format(BigDecimal.valueOf(minor, 2))
    }

    /** Minor units to the plain text used in the amount field, e.g. 1999 → "19.99". */
    fun minorToMajorInput(minor: Long): String {
        val digits = BigInteger.valueOf(minor).abs().toString().padStart(3, '0')
        return (if (minor < 0) "-" else "") + digits.dropLast(2) + "." + digits.takeLast(2)
    }

    /**
     * A slice's share of its chart as a whole percentage, rounded half up, e.g. 20000 of
     * 32000 → "63%". A share above 0 but below 0.5% is "<1%". Exact integer arithmetic.
     */
    fun formatShare(value: Long, total: Long): String {
        if (value <= 0 || total <= 0) return "0%"
        val part = BigInteger.valueOf(value)
        val whole = BigInteger.valueOf(total)
        if (part.multiply(TWO_HUNDRED) < whole) return "<1%"
        return "${part.multiply(TWO_HUNDRED).add(whole).divide(TWO.multiply(whole))}%"
    }
}
