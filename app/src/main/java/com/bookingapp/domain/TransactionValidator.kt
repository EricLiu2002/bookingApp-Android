package com.bookingapp.domain

import java.time.LocalDate

enum class Field { TYPE, AMOUNT, DATE, CATEGORY, NOTE }

/** A rejected create, update or import, naming each offending field. */
class ValidationException(val errors: Map<Field, String>) :
    IllegalArgumentException(errors.entries.joinToString("; ") { "${it.key.name.lowercase()}: ${it.value}" })

/** The field rules shared by single saves and imports (the backend's `TransactionInput`). */
object TransactionValidator {

    const val MAX_NOTE_LENGTH = 500

    sealed interface Result {
        data class Valid(val input: TransactionInput) : Result
        data class Invalid(val errors: Map<Field, String>) : Result
    }

    /** Checks an already-numeric input. The note is trimmed, and an empty note becomes null. */
    fun validate(input: TransactionInput, categoryExists: (Long) -> Boolean): Result {
        val errors = linkedMapOf<Field, String>()
        when {
            input.amountMinor <= 0 -> errors[Field.AMOUNT] = "Enter an amount greater than 0"
            input.amountMinor > Money.MAX_AMOUNT_MINOR -> errors[Field.AMOUNT] = "Amount is too large"
        }
        val categoryId = input.categoryId
        if (categoryId == null) {
            errors[Field.CATEGORY] = "Choose a category"
        } else if (!categoryExists(categoryId)) {
            errors[Field.CATEGORY] = "Category does not exist"
        }
        val note = normaliseNote(input.note)
        if (note != null && note.length > MAX_NOTE_LENGTH) {
            errors[Field.NOTE] = "Note must be at most $MAX_NOTE_LENGTH characters"
        }
        return if (errors.isEmpty()) Result.Valid(input.copy(note = note)) else Result.Invalid(errors)
    }

    /** Checks the Add/Edit form, whose amount is still text. */
    fun validateForm(
        type: TransactionType,
        amountText: String,
        date: LocalDate?,
        categoryId: Long?,
        note: String?,
        categoryExists: (Long) -> Boolean,
    ): Result {
        val errors = linkedMapOf<Field, String>()
        val amount = when (val parsed = Money.parseFormAmount(amountText)) {
            is Money.ParseResult.Ok -> parsed.minor
            is Money.ParseResult.Error -> {
                errors[Field.AMOUNT] = parsed.message
                null
            }
        }
        if (date == null) errors[Field.DATE] = "Choose a date"
        val numeric = TransactionInput(type, amount ?: 1, date ?: LocalDate.MIN, categoryId, note)
        when (val result = validate(numeric, categoryExists)) {
            is Result.Invalid -> result.errors.forEach { (field, message) -> errors.putIfAbsent(field, message) }
            is Result.Valid -> if (errors.isEmpty()) return result
        }
        return Result.Invalid(errors)
    }

    fun normaliseNote(note: String?): String? = note?.jstrip()?.ifEmpty { null }
}
