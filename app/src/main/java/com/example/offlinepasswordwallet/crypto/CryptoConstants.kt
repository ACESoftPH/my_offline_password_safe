package com.example.offlinepasswordwallet.crypto

/**
 * Central place for every cryptographic parameter used by the wallet.
 *
 * Design summary (see README.md "Encryption architecture" for the full write-up):
 *
 *  - The serialized JSON vault is sealed with **AES-256-GCM** (authenticated
 *    encryption) under a random 256-bit *Data Encryption Key* (DEK).
 *  - The DEK never leaves memory in the clear. It is *wrapped* (encrypted, again
 *    with AES-256-GCM) independently under:
 *      1. a *master key* derived from the master password with PBKDF2-HMAC-SHA256,
 *      2. a *recovery key* derived from the five normalized security answers with
 *         PBKDF2-HMAC-SHA256,
 *      3. optionally, an Android Keystore key gated by biometric authentication.
 *  - Because every wrapper protects the *same* DEK, changing the master password
 *    or the security answers only re-wraps the DEK; the (potentially large)
 *    ciphertext payload does not need to be re-encrypted.
 *
 * KDF choice: PBKDF2-HMAC-SHA256 is used instead of Argon2id. Argon2 on Android
 * requires bundling a native library; keeping the dependency surface minimal was
 * judged more valuable for auditability of an offline, security-sensitive app.
 * The iteration count below is deliberately high and the parameters are stored in
 * the vault header so they can be raised in future format versions without
 * breaking existing vaults.
 */
object CryptoConstants {

    /** Current on-disk vault format version. */
    const val VAULT_FORMAT_VERSION = 1

    // --- AES-GCM ---
    const val AES_ALGORITHM = "AES"
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_IV_LENGTH_BYTES = 12
    const val DEK_LENGTH_BITS = 256
    const val DEK_LENGTH_BYTES = DEK_LENGTH_BITS / 8

    // --- PBKDF2 ---
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * Iterations for the master-password KDF. OWASP's 2023 guidance for
     * PBKDF2-HMAC-SHA256 is >= 600,000; we use that as the floor.
     */
    const val PBKDF2_ITERATIONS_MASTER = 600_000

    /**
     * Iterations for the recovery KDF. Recovery answers are lower entropy than a
     * good master password, so this does not meaningfully change the security
     * ceiling, but we still use a high count to slow offline guessing of the
     * wrapped DEK if the vault file is stolen.
     */
    const val PBKDF2_ITERATIONS_RECOVERY = 600_000

    const val PBKDF2_KEY_LENGTH_BITS = 256

    const val SALT_LENGTH_BYTES = 16

    // --- Android Keystore ---
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val BIOMETRIC_KEY_ALIAS = "opw_biometric_dek_wrapping_key_v1"

    // --- Field / separator constants ---

    /** ASCII Unit Separator (0x1F) used to join normalized recovery answers before KDF. */
    val ANSWER_JOIN_SEPARATOR: String = 0x1F.toChar().toString()
}
