package com.acesoft.offlinepasswordwallet.data.model

import kotlinx.serialization.Serializable

/**
 * On-disk representation of the encrypted vault. This is the ONLY thing that
 * touches persistent storage. Every `*B64` field is standard Base64.
 *
 * Nothing sensitive is stored in the clear here:
 *  - [payload] is AES-256-GCM ciphertext of the serialized [VaultDocument].
 *  - [wrappedKeyMaster] / [wrappedKeyRecovery] are AES-256-GCM ciphertexts of the
 *    random 256-bit DEK, under keys derived from the master password and the five
 *    security answers respectively.
 *  - [securityQuestions] holds only the fixed *question text* (not answers).
 */
@Serializable
data class EncryptedVaultFile(
    val formatVersion: Int,
    val kdf: KdfParamsDto,
    val masterSaltB64: String,
    val recoverySaltB64: String,
    val wrappedKeyMaster: EncryptedBlobDto,
    val wrappedKeyRecovery: EncryptedBlobDto,
    val payload: EncryptedBlobDto,
    val securityQuestions: List<String>,
    /** Opaque marker so first-run detection never depends on a bare boolean pref. */
    val vaultId: String,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    /**
     * Monotonic write counter, incremented on every persisted change. From format
     * version 2 it is part of the payload's AES-GCM associated data, so an old
     * `payload` blob cannot be pasted back over a newer header without the tag
     * check failing. Defaults to 1 when reading a v1 file that predates it.
     */
    val revision: Long = 1L,
)

@Serializable
data class KdfParamsDto(
    val algorithm: String,
    val masterIterations: Int,
    val recoveryIterations: Int,
    val keyLengthBits: Int,
)

/** AES-GCM ciphertext plus its unique IV. The GCM tag is appended to [ciphertextB64]. */
@Serializable
data class EncryptedBlobDto(
    val ivB64: String,
    val ciphertextB64: String,
)
