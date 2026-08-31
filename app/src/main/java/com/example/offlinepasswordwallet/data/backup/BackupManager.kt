package com.example.offlinepasswordwallet.data.backup

import com.example.offlinepasswordwallet.data.model.ImportMode
import com.example.offlinepasswordwallet.data.model.VaultDocument
import com.example.offlinepasswordwallet.data.repository.VaultRepository

/**
 * Coordinates encrypted-backup export and restore on top of [VaultRepository].
 *
 *  - **Export** requires the vault to be unlocked; it re-encrypts the current
 *    in-memory document under a user-chosen backup passphrase (see [BackupCodec]).
 *  - **Restore** works from any state, including a fresh install: the caller
 *    decrypts the backup with [previewAndDecrypt], then either merges the entries
 *    into an already-unlocked vault ([mergeIntoUnlockedVault]) or rebuilds a
 *    brand-new vault around them, choosing a new master password and new security
 *    answers ([restoreAsNewVault]).
 */
class BackupManager(
    private val repository: VaultRepository,
    private val appVersionName: String,
    private val codec: BackupCodec = BackupCodec(),
) {
    /** Serializes an encrypted backup of the currently unlocked vault. */
    fun exportBytes(passphrase: CharArray): Result<ByteArray> = runCatching {
        val document = repository.currentDocument()
            ?: error("Unlock the vault before exporting a backup.")
        codec.create(passphrase, document, appVersionName)
    }

    /** Decodes + decrypts a backup file, returning its metadata and content. */
    fun previewAndDecrypt(
        bytes: ByteArray,
        passphrase: CharArray,
    ): Result<Pair<BackupPreview, VaultDocument>> = runCatching {
        val file = codec.decode(bytes)
        val document = codec.open(file, passphrase)
        BackupPreview(file.entryCount, file.createdAtEpochMillis, file.appVersionName) to document
    }

    /**
     * Rebuilds the vault from [document] under a NEW master password and NEW
     * security answers. If a vault already exists it is atomically replaced — the
     * caller MUST have taken an explicit confirmation first.
     */
    suspend fun restoreAsNewVault(
        document: VaultDocument,
        newMasterPassword: CharArray,
        newRawAnswers: List<String>,
    ): Result<Unit> =
        repository.replaceVaultWithDocument(newMasterPassword, newRawAnswers, document)

    /** Adds the backup's entries to the already-unlocked vault. */
    suspend fun mergeIntoUnlockedVault(document: VaultDocument, mode: ImportMode): Result<Unit> =
        repository.importEntries(document.entries, mode)
}
