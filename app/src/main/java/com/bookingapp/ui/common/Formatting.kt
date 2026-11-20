package com.bookingapp.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.bookingapp.domain.Money
import com.bookingapp.domain.TransactionType
import com.bookingapp.ui.theme.LedgerTheme
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The display currency (BuildConfig.CURRENCY in the app). */
val LocalCurrency = staticCompositionLocalOf { "USD" }

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)

fun monthLabel(month: YearMonth): String = MONTH_LABEL.format(month)

fun typeLabel(type: TransactionType): String = when (type) {
    TransactionType.COST -> "Cost"
    TransactionType.EARNING -> "Earning"
}

@Composable
fun formatMoney(minor: Long): String = Money.format(minor, LocalCurrency.current)

/** An amount; negative amounts get a leading minus and the negative colour. */
@Composable
fun MoneyText(
    minor: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    type: TransactionType? = null,
    bold: Boolean = false,
) {
    val color = when {
        minor < 0 -> LedgerTheme.colors.negative
        type == TransactionType.EARNING -> LedgerTheme.colors.income
        type == TransactionType.COST -> LedgerTheme.colors.cost
        else -> LocalContentColor.current
    }
    Text(
        formatMoney(minor),
        modifier = if (minor < 0) modifier.semantics { stateDescription = "negative" } else modifier,
        color = color,
        style = style,
        fontWeight = if (bold) FontWeight.SemiBold else null,
    )
}
