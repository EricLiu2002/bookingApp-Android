package com.bookingapp.ui.navigation

import com.bookingapp.domain.TransactionType
import kotlinx.serialization.Serializable
import java.time.YearMonth

/** Typed routes (design decision 8). Months are `YYYY-MM` text; null means the current month. */
@Serializable
data class Dashboard(val month: String? = null)

/** @param type `COST`, `EARNING` or `ALL`; null means the default filter (Costs). */
@Serializable
data class Transactions(val month: String? = null, val type: String? = null) {
    companion object {
        const val ALL = "ALL"

        fun of(month: YearMonth, type: TransactionType?) = Transactions(month.toString(), type?.name ?: ALL)
    }
}

/** Add when [id] is null, otherwise edit that transaction. */
@Serializable
data class Edit(val id: Long? = null)

@Serializable
data object Import
