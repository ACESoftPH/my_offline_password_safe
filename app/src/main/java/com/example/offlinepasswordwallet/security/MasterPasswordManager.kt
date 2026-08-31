package com.example.offlinepasswordwallet.security

import com.example.offlinepasswordwallet.data.repository.VaultRepository
import com.example.offlinepasswordwallet.password.PasswordStrength

/**
 * Facade for master-password operations (§2, §28). Adds policy checks on top of
 * [VaultRepository], which owns the actual key wrapping / atomic persistence.
 *
 * The master password is only ever held as a [CharArray] here and by callers, and
 * is never persisted in any form (§1). It is used solely to derive the key that
 * wraps the vault DEK.
 */
class MasterPasswordManager(
    private val repository: VaultRepository,
) {
    fun policyError(password: CharArray): String? = PasswordStrength.masterPolicyError(password)

    fun strengthFraction(password: String): Float = PasswordStrength.evaluate(password).fraction

    suspend fun createVault(
        masterPassword: CharArray,
        confirmPassword: CharArray,
        rawAnswers: List<String>,
    ): Result<Unit> {
        policyError(masterPassword)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!masterPassword.contentEquals(confirmPassword)) {
            return Result.failure(IllegalArgumentException("The two passwords do not match."))
        }
        return repository.createVault(masterPassword, rawAnswers)
    }

    suspend fun unlock(masterPassword: CharArray): Result<Unit> =
        repository.unlockWithMaster(masterPassword)

    suspend fun changeMasterPassword(
        current: CharArray,
        newPassword: CharArray,
        confirmPassword: CharArray,
    ): Result<Unit> {
        policyError(newPassword)?.let { return Result.failure(IllegalArgumentException(it)) }
        if (!newPassword.contentEquals(confirmPassword)) {
            return Result.failure(IllegalArgumentException("The two passwords do not match."))
        }
        return repository.changeMasterPassword(current, newPassword)
    }

    suspend fun changeSecurityAnswers(
        currentMaster: CharArray,
        newRawAnswers: List<String>,
    ): Result<Unit> = repository.changeSecurityAnswers(currentMaster, newRawAnswers)
}
