package com.acesoftph.offlinepasswordwallet.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {

    private val allowedSpecial = PasswordGenerator.SPECIAL.toSet()

    @Test
    fun `respects minimum length`() {
        val pw = PasswordGenerator.generate(PasswordGenerator.MIN_LENGTH, useSpecialChars = true)
        assertEquals(PasswordGenerator.MIN_LENGTH, pw.length)
    }

    @Test
    fun `respects maximum length`() {
        val pw = PasswordGenerator.generate(PasswordGenerator.MAX_LENGTH, useSpecialChars = true)
        assertEquals(PasswordGenerator.MAX_LENGTH, pw.length)
    }

    @Test
    fun `produces the exact requested length`() {
        for (len in listOf(8, 12, 20, 33, 64)) {
            assertEquals(len, PasswordGenerator.generate(len, useSpecialChars = false).length)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects length below minimum`() {
        PasswordGenerator.generate(PasswordGenerator.MIN_LENGTH - 1, useSpecialChars = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects length above maximum`() {
        PasswordGenerator.generate(PasswordGenerator.MAX_LENGTH + 1, useSpecialChars = true)
    }

    @Test
    fun `with special chars satisfies full policy for many samples`() {
        repeat(2_000) {
            val pw = PasswordGenerator.generate(16, useSpecialChars = true)
            assertTrue("lowercase", pw.any { it in 'a'..'z' })
            assertTrue("uppercase", pw.any { it in 'A'..'Z' })
            assertTrue("digit", pw.any { it in '0'..'9' })
            assertTrue("special", pw.any { it in allowedSpecial })
            assertTrue(
                "only allowed chars",
                pw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in allowedSpecial },
            )
        }
    }

    @Test
    fun `without special chars still has lower upper digit and no symbols`() {
        repeat(2_000) {
            val pw = PasswordGenerator.generate(12, useSpecialChars = false)
            assertTrue(pw.any { it in 'a'..'z' })
            assertTrue(pw.any { it in 'A'..'Z' })
            assertTrue(pw.any { it in '0'..'9' })
            assertTrue(pw.all { it.isLetterOrDigit() })
            assertFalse(pw.any { it in allowedSpecial })
        }
    }

    @Test
    fun `never contains disallowed punctuation`() {
        val disallowed = "`':;/\\,\"".toSet()
        repeat(2_000) {
            val pw = PasswordGenerator.generate(24, useSpecialChars = true)
            assertFalse(pw.any { it in disallowed })
        }
    }

    @Test
    fun `generates different passwords`() {
        val set = HashSet<String>()
        repeat(500) { set.add(PasswordGenerator.generate(20, useSpecialChars = true)) }
        // 500 random 20-char passwords should essentially never collide.
        assertTrue(set.size >= 499)
    }

    @Test
    fun `required characters are not always at fixed positions`() {
        // Track where the first uppercase char lands across many samples; a fixed
        // pattern would concentrate on one index.
        val positions = IntArray(10)
        repeat(5_000) {
            val pw = PasswordGenerator.generate(10, useSpecialChars = true)
            positions[pw.indexOfFirst { it in 'A'..'Z' }]++
        }
        assertTrue("no position holds more than 40% of first-uppercase hits",
            positions.all { it < 5_000 * 0.40 })
    }
}
