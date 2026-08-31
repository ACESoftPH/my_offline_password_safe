package com.example.offlinepasswordwallet.crypto

import java.text.Normalizer
import java.util.Locale

/**
 * The five fixed recovery questions and the canonical normalization applied to
 * their answers before they are fed to the KDF.
 *
 * Normalization (documented, deliberate, and stable across app versions):
 *  1. Unicode normalization to **NFKC** (compatibility composition) so visually
 *     identical input encoded differently still matches.
 *  2. Trim leading/trailing Unicode whitespace.
 *  3. Collapse every run of internal whitespace to a single ASCII space.
 *  4. Lowercase using [Locale.ROOT] (locale-independent). **Case is ignored.**
 *
 * The normalized answers are joined with the ASCII Unit Separator (0x1F), which
 * cannot appear in normalized text, so answers like ("a", "bc") and ("ab", "c")
 * can never collide. The joined string is the KDF passphrase.
 *
 * SECURITY NOTE surfaced to the user during setup: answers derived from personal
 * facts are far lower entropy than a random recovery key, so this recovery path
 * is intentionally the weakest link and is rate-limited.
 */
object SecurityAnswers {

    val QUESTIONS: List<String> = listOf(
        "What was the name of your first school?",
        "What was the name of your favorite pet?",
        "What is your mother's maiden name?",
        "What is your father's middle name?",
        "In what year did you graduate from college?",
    )

    const val REQUIRED_COUNT = 5

    private val WHITESPACE = Regex("\\s+")

    fun normalize(answer: String): String {
        val nfkc = Normalizer.normalize(answer, Normalizer.Form.NFKC)
        val collapsed = nfkc.trim().replace(WHITESPACE, " ")
        return collapsed.lowercase(Locale.ROOT)
    }

    /**
     * Builds the KDF passphrase from exactly five raw answers. Returns a
     * [CharArray] the caller should zero after derivation.
     */
    fun toPassphrase(rawAnswers: List<String>): CharArray {
        require(rawAnswers.size == REQUIRED_COUNT) {
            "Exactly $REQUIRED_COUNT answers are required"
        }
        val joined = rawAnswers.joinToString(CryptoConstants.ANSWER_JOIN_SEPARATOR) { normalize(it) }
        return joined.toCharArray()
    }

    /** True if every answer is non-blank after trimming. */
    fun allAnswered(rawAnswers: List<String>): Boolean =
        rawAnswers.size == REQUIRED_COUNT && rawAnswers.all { it.trim().isNotEmpty() }
}
