package com.bookingapp.domain

import java.time.LocalDate

enum class TransactionType { COST, EARNING }

data class Category(val id: Long, val name: String)

/** A transaction as stored in the ledger, joined with its category name. */
data class Transaction(
    val id: Long,
    val type: TransactionType,
    val amountMinor: Long,
    val date: LocalDate,
    val categoryId: Long,
    val categoryName: String,
    val note: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** The fields a user (or an import row) supplies for a create or update. */
data class TransactionInput(
    val type: TransactionType,
    val amountMinor: Long,
    val date: LocalDate,
    val categoryId: Long?,
    val note: String?,
)

/** One (category, type) total in a period. */
data class CategoryTotal(
    val categoryId: Long,
    val categoryName: String,
    val type: TransactionType,
    val totalMinor: Long,
)

data class Summary(
    val incomeMinor: Long,
    val outcomeMinor: Long,
    val breakdown: List<CategoryTotal>,
) {
    val balanceMinor: Long get() = incomeMinor - outcomeMinor

    companion object {
        val EMPTY = Summary(0, 0, emptyList())
    }
}

/** Java's String.strip(): trims Character.isWhitespace only, matching the backend exactly. */
internal fun String.jstrip(): String = trim { Character.isWhitespace(it) }

/** Java's String.isBlank(). */
internal fun String.jIsBlank(): Boolean = all { Character.isWhitespace(it) }
