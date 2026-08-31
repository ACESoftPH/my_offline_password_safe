package com.aldinson.offlinepasswordwallet.security

import com.aldinson.offlinepasswordwallet.crypto.AeadDecryptionException
import com.aldinson.offlinepasswordwallet.data.repository.VaultRepository

/** Outcome of submitting the five recovery answers. */
sealed interface RecoveryResult {
    /** Answers verified; the vault is now unlocked via the recovery key. */
    data object Verified : RecoveryResult

    /** One or more answers were wrong. [lockedOutMillis] > 0 if now throttled. */
    data class Incorrect(val lockedOutMillis: Long) : RecoveryResult

    /** Attempt refused because a lockout is already in effect. */
    data class LockedOut(val remainingMillis: Long) : RecoveryResult

    /** The vault file is missing/corrupt — recovery cannot proceed. */
    data class Unusable(val message: String) : RecoveryResult
}

/**
 * Orchestrates the "Reset Master Password" flow (§3, §50):
 *
 *   five answers -> secure verification (GCM tag on the recovery-wrapped DEK)
 *   -> authorized recovery unlock -> caller sets a NEW master password
 *   -> DEK re-wrapped -> continue.
 *
 * The original master password is never stored, retrieved, or displayed. Wrong
 * answers never touch the vault file, so a failed attempt cannot destroy data
 * (§3 "must not destroy the existing vault"). Guessing is rate-limited by
 * [RecoveryRateLimiter], whose state persists across app restarts.
 */
class RecoveryManager(
    private val repository: VaultRepository,
    private val rateLimiter: RecoveryRateLimiter,
) {
    val questions: List<String> = com.aldinson.offlinepasswordwallet.crypto.SecurityAnswers.QUESTIONS

    fun lockoutRemainingMillis(): Long = rateLimiter.remainingLockoutMillis()

    suspend fun submitAnswers(rawAnswers: List<String>): RecoveryResult {
        val remaining = rateLimiter.remainingLockoutMillis()
        if (remaining > 0) return RecoveryResult.LockedOut(remaining)

        val result = repository.unlockWithRecoveryAnswers(rawAnswers)
        return result.fold(
            onSuccess = {
                rateLimiter.reset()
                RecoveryResult.Verified
            },
            onFailure = { error ->
                when (error) {
                    is AeadDecryptionException -> {
                        rateLimiter.recordFailure()
                        RecoveryResult.Incorrect(rateLimiter.remainingLockoutMillis())
                    }
                    else -> RecoveryResult.Unusable(
                        error.message ?: "The vault file could not be read.",
                    )
                }
            },
        )
    }

    /** Call only after [submitAnswers] returned [RecoveryResult.Verified]. */
    suspend fun applyNewMasterPassword(newPassword: CharArray): Result<Unit> =
        repository.setMasterPasswordAfterRecovery(newPassword)
}
