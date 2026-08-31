package com.example.offlinepasswordwallet.crypto

import java.security.SecureRandom

/**
 * Single source of cryptographically secure randomness for the whole app.
 *
 * We deliberately expose only [SecureRandom]. `java.util.Random`, `Math.random()`,
 * timestamps and other predictable sources must never be used for salts, IVs,
 * key material or generated passwords.
 *
 * On modern Android the default `SecureRandom()` constructor is already seeded
 * from the OS CSPRNG (`/dev/urandom` via the AndroidOpenSSL provider); we do not
 * call `setSeed` (which would only *add* entropy and could otherwise be misused).
 */
object SecureRandomProvider {

    /** Thread-safe; [SecureRandom] instances are safe to share across threads. */
    val secureRandom: SecureRandom by lazy { SecureRandom() }

    fun nextBytes(length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        val out = ByteArray(length)
        secureRandom.nextBytes(out)
        return out
    }

    /**
     * Uniformly distributed integer in [0, bound) without modulo bias.
     */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return secureRandom.nextInt(bound)
    }
}
