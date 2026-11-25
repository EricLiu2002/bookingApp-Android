package com.bookingapp.domain

import com.bookingapp.domain.TransactionValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TransactionValidatorTest {

    private val today = LocalDate.of(2026, 10, 5)
    private val exists: (Long) -> Boolean = { it == 1L }

    private fun input(amount: Long = 1999, categoryId: Long? = 1, note: String? = null) =
        TransactionInput(TransactionType.COST, amount, today, categoryId, note)

    private fun errors(result: Result) = (result as Result.Invalid).errors

    @Test
    fun validInputPasses() {
        assertEquals(Result.Valid(input()), TransactionValidator.validate(input(), exists))
    }

    @Test
    fun amountMustBePositiveAndAtMostTheCap() {
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = 0), exists)).keys)
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = -1), exists)).keys)
        assertEquals(setOf(Field.AMOUNT), errors(TransactionValidator.validate(input(amount = Money.MAX_AMOUNT_MINOR + 1), exists)).keys)
        assertTrue(TransactionValidator.validate(input(amount = Money.MAX_AMOUNT_MINOR), exists) is Result.Valid)
    }

    @Test
    fun categoryRequiredAndMustExist() {
        assertEquals(setOf(Field.CATEGORY), errors(TransactionValidator.validate(input(categoryId = null), exists)).keys)
        assertEquals(setOf(Field.CATEGORY), errors(TransactionValidator.validate(input(categoryId = 2), exists)).keys)
    }

    @Test
    fun noteLimitAndTrimming() {
        assertEquals(setOf(Field.NOTE), errors(TransactionValidator.validate(input(note = "x".repeat(501)), exists)).keys)
        assertTrue(TransactionValidator.validate(input(note = "x".repeat(500)), exists) is Result.Valid)
        assertEquals("Costco", (TransactionValidator.validate(input(note = "  Costco "), exists) as Result.Valid).input.note)
        assertNull((TransactionValidator.validate(input(note = "   "), exists) as Result.Valid).input.note)
        // Trimming happens before the length check.
        assertTrue(TransactionValidator.validate(input(note = " " + "x".repeat(500) + " "), exists) is Result.Valid)
    }

    @Test
    fun formRejectsInvalidAmountText() {
        listOf("0", "-3", "1.999", "abc", "", "90071992547409.92").forEach { text ->
            val result = TransactionValidator.validateForm(TransactionType.COST, text, today, 1, null, exists)
            assertEquals(text, setOf(Field.AMOUNT), errors(result).keys)
        }
    }

    @Test
    fun formCollectsEveryFieldError() {
        val result = TransactionValidator.validateForm(TransactionType.COST, "abc", null, null, "x".repeat(501), exists)
        assertEquals(setOf(Field.AMOUNT, Field.DATE, Field.CATEGORY, Field.NOTE), errors(result).keys)
    }

    @Test
    fun formConvertsExactly() {
        val result = TransactionValidator.validateForm(TransactionType.EARNING, "0.10", today, 1, " ", exists)
        assertEquals(Result.Valid(TransactionInput(TransactionType.EARNING, 10, today, 1, null)), result)
    }
}
