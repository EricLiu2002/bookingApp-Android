package com.bookingapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Ledger-specific colours on top of Material 3. */
@Immutable
data class LedgerColors(val income: Color, val cost: Color, val negative: Color)

private val LightLedger = LedgerColors(income = Color(0xFF15803D), cost = Color(0xFF1D4ED8), negative = Color(0xFFB91C1C))
private val DarkLedger = LedgerColors(income = Color(0xFF4ADE80), cost = Color(0xFF93C5FD), negative = Color(0xFFF87171))

val LocalLedgerColors = staticCompositionLocalOf { LightLedger }

private val LightScheme = lightColorScheme(primary = Color(0xFF2563EB), secondary = Color(0xFF0891B2))
private val DarkScheme = darkColorScheme(primary = Color(0xFF93C5FD), secondary = Color(0xFF67E8F9))

@Composable
fun BookingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLedgerColors provides if (darkTheme) DarkLedger else LightLedger) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
    }
}

object LedgerTheme {
    val colors: LedgerColors
        @Composable get() = LocalLedgerColors.current
}
