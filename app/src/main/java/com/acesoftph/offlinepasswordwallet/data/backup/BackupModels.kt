package com.acesoftph.offlinepasswordwallet.data.backup

import com.acesoftph.offlinepasswordwallet.data.model.EncryptedBlobDto
import kotlinx.serialization.Serializable

/**
 * On-"disk" (i.e. exported-file) representation of a portable **encrypted
 * backup**.
 *
 * Unlike the live `vault.json`, a backup is protected by a *dedicated backup
 * passphrase* that is independent of the master password, the security answers,
 * and any device-bound Android Keystore key. That makes it safe to move to
 * another phone or keep for disaster recovery: it can be restored anywhere, and
 * its strength is exactly the strength of the backup passphrase.
 *
 * It contains ONLY the encrypted vault document (entries + fields). It does not
 * contain the master password, the security-question answers, or the biometric
 * key material.
 *
 * Every `*B64` field is standard Base64. [payload] is AES-256-GCM ciphertext of
 * the serialized `VaultDocument`.
 */
@Serializable
data class EncryptedBackupFile(
    val magic: String,
    val formatVersion: Int,
    val kdf: BackupKdfDto,
    val saltB64: String,
    val payload: EncryptedBlobDto,
    /** Non-sensitive metadata shown on the restore confirmation screen. */
    val entryCount: Int,
    val createdAtEpochMillis: Long,
    val appVersionName: String,
)

@Serializable
data class BackupKdfDto(
    val algorithm: String,
    val iterations: Int,
    val keyLengthBits: Int,
)

/** Non-sensitive summary of a decrypted backup, for the confirmation UI. */
data class BackupPreview(
    val entryCount: Int,
    val createdAtEpochMillis: Long,
    val appVersionName: String,
)
