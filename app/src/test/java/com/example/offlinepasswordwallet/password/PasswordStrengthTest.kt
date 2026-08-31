package com.example.offlinepasswordwallet.password

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
}
