package com.example.offlinepasswordwallet.password

import kotlin.math.ln
import kotlin.math.roundToInt

enum class StrengthLevel(val label: String) {
    VERY_WEAK("Very weak"),
    WEAK("Weak"),
    FAIR("Fair"),
    STRONG("Strong"),
    VERY_STRONG("Very strong"),
}

data class StrengthResult(
    val level: StrengthLevel,
    val estimatedBits: Int,
    /** 0f..1f for a progress bar. */
    val fraction: Float,
)

/**
 * Lightweight offline password-strength estimate used for the master-password
 * meter (§2) and entry passwords. This is a heuristic Shannon-style estimate — a
 * rough guide only; it never blocks the user beyond the documented minimum
 * policy.
 */
object PasswordStrength {

    /** Master password policy (§2): reasonable minimum, no artificial maximum. */
    const val MASTER_MIN_LENGTH = 10

    fun masterPolicyError(password: CharArray): String? {
        if (password.size < MASTER_MIN_LENGTH) {
            return "Use at least $MASTER_MIN_LENGTH characters."
        }
        val s = String(password)
        val classes = countClasses(s)
        if (classes < 3) {
            return "Mix upper case, lower case, digits and symbols (at least 3 kinds)."
        }
        return null
    }

    fun evaluate(password: String): StrengthResult {
        if (password.isEmpty()) {
            return StrengthResult(StrengthLevel.VERY_WEAK, 0, 0f)
        }
        // Unicode-aware: a strong Greek/Cyrillic/CJK passphrase must not be scored
        // as if it contained no letters at all.
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 32
        // Letters with no upper/lower distinction (CJK, Hebrew, …) still add entropy.
        if (pool == 0 && password.any { it.isLetter() }) pool += 26

        val uniqueRatio = password.toSet().size.toDouble() / password.length
        val rawBits = password.length * (ln(pool.toDouble().coerceAtLeast(2.0)) / ln(2.0))
        val bits = (rawBits * (0.35 + 0.65 * uniqueRatio)).roundToInt()

        val level = when {
            bits < 28 -> StrengthLevel.VERY_WEAK
            bits < 40 -> StrengthLevel.WEAK
            bits < 60 -> StrengthLevel.FAIR
            bits < 90 -> StrengthLevel.STRONG
            else -> StrengthLevel.VERY_STRONG
        }
        val fraction = (bits / 100f).coerceIn(0f, 1f)
        return StrengthResult(level, bits, fraction)
    }

    /**
     * Unicode-aware character-class count. Using ASCII ranges here would reject a
     * perfectly strong non-Latin passphrase for "not enough kinds of character",
     * and would also mis-score caseless scripts. A script with no case distinction
     * (CJK, Hebrew, Arabic, …) counts as one class on its own.
     */
    private fun countClasses(s: String): Int {
        var c = 0
        if (s.any { it.isLowerCase() }) c++
        if (s.any { it.isUpperCase() }) c++
        if (s.any { it.isDigit() }) c++
        if (s.any { !it.isLetterOrDigit() }) c++
        if (s.any { it.isLetter() && !it.isLowerCase() && !it.isUpperCase() }) c++
        return c
    }
}
