package com.acesoftph.offlinepasswordwallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAnswersTest {

    @Test
    fun `exactly five fixed questions`() {
        assertEquals(5, SecurityAnswers.QUESTIONS.size)
        assertEquals(5, SecurityAnswers.REQUIRED_COUNT)
    }

    @Test
    fun `normalization trims collapses whitespace and lowercases`() {
        assertEquals("st marys", SecurityAnswers.normalize("  St   Marys  "))
        assertEquals("fluffy", SecurityAnswers.normalize("Fluffy"))
        assertEquals("fluffy", SecurityAnswers.normalize("FLUFFY"))
    }

    @Test
    fun `normalization applies NFKC`() {
        // Fullwidth latin 'A' (U+FF21) normalizes (NFKC) to ASCII 'a' after lowercasing.
        assertEquals("abc", SecurityAnswers.normalize("ＡＢＣ"))
    }

    @Test
    fun `passphrase join is unambiguous`() {
        val a = SecurityAnswers.toPassphrase(listOf("a", "b", "c", "d", "ef"))
        val b = SecurityAnswers.toPassphrase(listOf("a", "b", "c", "de", "f"))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `passphrase is stable regardless of case and padding`() {
        val a = String(SecurityAnswers.toPassphrase(listOf(" First ", "Pet", "Maiden", "Middle", "2011")))
        val b = String(SecurityAnswers.toPassphrase(listOf("first", "pet", "maiden", "middle", "2011")))
        assertEquals(a, b)
    }

    @Test
    fun `allAnswered requires all five non-blank`() {
        assertTrue(SecurityAnswers.allAnswered(listOf("a", "b", "c", "d", "e")))
        assertFalse(SecurityAnswers.allAnswered(listOf("a", "b", "", "d", "e")))
        assertFalse(SecurityAnswers.allAnswered(listOf("a", "b", "c", "d")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toPassphrase rejects wrong count`() {
        SecurityAnswers.toPassphrase(listOf("a", "b", "c"))
    }
}
