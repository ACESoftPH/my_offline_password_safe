package com.example.offlinepasswordwallet.password

import com.example.offlinepasswordwallet.crypto.SecureRandomProvider

/**
 * Cryptographically secure password generator (§10–§15).
 *
 * Randomness: every character and every shuffle step comes from
 * [SecureRandomProvider] (a single shared [java.security.SecureRandom]).
 * `Math.random()`, `java.util.Random`, timestamps, usernames and dictionary words
 * are never used.
 *
 * Guarantees for a returned password of the requested [length]:
 *  - contains >= 1 lowercase, >= 1 uppercase, >= 1 digit;
 *  - contains >= 1 allowed special char *iff* `useSpecialChars` is true;
 *  - contains ONLY characters from the enabled sets;
 *  - the positions of the guaranteed characters are themselves randomized
 *    (Fisher–Yates shuffle), so there is no fixed pattern.
 */
object PasswordGenerator {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    const val DEFAULT_LENGTH = 20

    const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"

    /** The ONLY permitted special characters (§11). Order is irrelevant. */
    const val SPECIAL = "!@#\$%^&*()_-+=<>.?{[}]~|"

    fun generate(length: Int, useSpecialChars: Boolean): String {
        require(length in MIN_LENGTH..MAX_LENGTH) {
            "length must be in $MIN_LENGTH..$MAX_LENGTH"
        }

        val requiredClasses = buildList {
            add(LOWERCASE)
            add(UPPERCASE)
            add(DIGITS)
            if (useSpecialChars) add(SPECIAL)
        }
        val fullPool = requiredClasses.joinToString("")

        val chars = CharArray(length)
        var i = 0

        // One guaranteed character from each required class.
        for (cls in requiredClasses) {
            chars[i++] = cls[SecureRandomProvider.nextInt(cls.length)]
        }
        // Remaining characters from the full enabled pool.
        while (i < length) {
            chars[i++] = fullPool[SecureRandomProvider.nextInt(fullPool.length)]
        }

        shuffleInPlace(chars)
        return String(chars)
    }

    /** Fisher–Yates using the CSPRNG so guaranteed chars land in random spots. */
    private fun shuffleInPlace(array: CharArray) {
        for (idx in array.size - 1 downTo 1) {
            val j = SecureRandomProvider.nextInt(idx + 1)
            val tmp = array[idx]
            array[idx] = array[j]
            array[j] = tmp
        }
    }
}
