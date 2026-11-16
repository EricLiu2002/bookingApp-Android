package com.bookingapp.data

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The backend's fixed-width UTC timestamp format, truncated to microseconds. Fixed width
 * makes text order equal time order, which same-day list ordering relies on.
 */
object Timestamps {
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun now(clock: Clock): String = FORMAT.format(clock.instant().truncatedTo(ChronoUnit.MICROS))
}

/** A half-open date range `[from, to)` as ISO text, which compares like dates. */
data class DateRange(val from: String, val to: String) {
    companion object {
        fun of(month: YearMonth) = DateRange(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())

        fun ofYear(year: Int): DateRange {
            require(year in 1..9999) { "year must be between 1 and 9999" }
            val first = LocalDate.of(year, 1, 1)
            // For 9999 the end is "10000-01-01" as text, which still sorts after every 9999 date.
            val end = if (year == 9999) "9999-12-31￿" else first.plusYears(1).toString()
            return DateRange(first.toString(), end)
        }
    }
}
