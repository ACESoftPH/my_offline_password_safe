package com.acesoftph.offlinepasswordwallet.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthTest {

    @Test
    fun `master policy rejects short passwords`() {
        assertNotNull(PasswordStrength.masterPolicyError("short1!A".toCharArray()))
    }

    @Test
    fun `master policy rejects low-variety passwords`() {
        assertNotNull(PasswordStrength.masterPolicyError("alllowercaseletters".toCharArray()))
    }

    @Test
    fun `master policy accepts a long varied passphrase`() {
        assertNull(PasswordStrength.masterPolicyError("Correct-Horse-Battery-7".toCharArray()))
    }

    @Test
    fun `strength increases with length and variety`() {
        val weak = PasswordStrength.evaluate("aaaaaa").estimatedBits
        val strong = PasswordStrength.evaluate("G7\$kW2p!qLz9#rT4mB1x").estimatedBits
        assertTrue(strong > weak)
        assertTrue(strong >= 60)
    }

    @Test
    fun `empty password is very weak`() {
        assertEquals(StrengthLevel.VERY_WEAK, PasswordStrength.evaluate("").level)
    }

    // --- regression: the class check used to be ASCII-only --------------------

    @Test
    fun `a strong non-Latin passphrase is accepted by the policy`() {
        // Cyrillic upper + lower + digit + symbol: four real classes, previously
        // counted as one (only the symbol matched an ASCII range).
        assertNull(PasswordStrength.masterPolicyError("Пароль-Надёжный7!".toCharArray()))
        // Greek.
        assertNull(PasswordStrength.masterPolicyError("Ασφάλεια-Κωδικός9!".toCharArray()))
    }

    @Test
    fun `a caseless script still counts as a character class`() {
        assertNull(PasswordStrength.masterPolicyError("日本語のパスワード42!".toCharArray()))
    }

    @Test
    fun `non-Latin letters contribute to the entropy estimate`() {
        assertTrue(PasswordStrength.evaluate("Пароль-Надёжный7!").estimatedBits >= 60)
    }

    @Test
    fun `a low-variety password is still rejected`() {
        assertNotNull(PasswordStrength.masterPolicyError("паролыпаролыпаролы".toCharArray()))
    }
}
