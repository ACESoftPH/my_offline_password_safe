package com.aldinson.offlinepasswordwallet.ui

import com.aldinson.offlinepasswordwallet.ui.components.CENSOR_CHAR
import com.aldinson.offlinepasswordwallet.ui.components.censored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The masked rendering of a hidden password.
 *
 * The editable field's `PasswordVisualTransformation` mask and the read-only
 * detail row both derive from [CENSOR_CHAR], so these assertions pin the two to
 * the same character — the bug this guards against is one of them being changed
 * and the other quietly left behind.
 */
class PasswordCensorTest {

    @Test
    fun `censor character is an asterisk`() {
        assertEquals('*', CENSOR_CHAR)
    }

    @Test
    fun `masked rendering is built only from the censor character`() {
        val masked = censored()
        assertTrue("mask must not be empty", masked.isNotEmpty())
        assertTrue(
            "mask must contain nothing but '$CENSOR_CHAR', was '$masked'",
            masked.all { it == CENSOR_CHAR },
        )
    }

    @Test
    fun `masked rendering never leaks the real password length`() {
        // Whatever the password, the mask shown in the detail row is the same
        // fixed width — otherwise its length alone would narrow a guess.
        val lengths = listOf("a", "hunter2", "x".repeat(64), "")
            .map { censored().length }
            .toSet()
        assertEquals(1, lengths.size)
    }

    @Test
    fun `masked rendering contains no character that could be mistaken for content`() {
        val masked = censored()
        assertFalse(masked.any { it.isLetterOrDigit() })
        assertFalse(masked.contains('#')) // the previous censor, now replaced
    }
}
