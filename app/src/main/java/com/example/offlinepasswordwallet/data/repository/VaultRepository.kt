package com.example.offlinepasswordwallet.data.repository

import com.example.offlinepasswordwallet.crypto.UnlockedVault
import com.example.offlinepasswordwallet.crypto.VaultCryptoException
import com.example.offlinepasswordwallet.data.model.EncryptedVaultFile
import com.example.offlinepasswordwallet.data.model.ImportMode
import com.example.offlinepasswordwallet.data.model.VaultDocument
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.storage.VaultCodec
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.SecretKey

sealed interface VaultState {
    /** No vault file on disk yet — first run. */
    data object Uninitialized : VaultState

    /** A vault exists but is not decrypted in memory. */
    data object Locked : VaultState

    /** Decrypted and available. [entries] is a snapshot. */
    data class Unlocked(val entries: List<VaultEntry>) : VaultState
}

/**
 * The one place decrypted vault data lives while the app is unlocked, and the one
 * place that writes the encrypted file.
 *
 * Threading: every mutating operation runs under [mutex] so vault writes are
 * serialized. Persistence uses [VaultCodec] + [VaultFileStore], which performs an
 * atomic, verified, crash-safe replace (§43).
 *
 * On [lock] every decrypted reference is dropped and key bytes are best-effort
 * zeroed. JVM `String` immutability means field *values* cannot be reliably wiped
 * — this is documented in the README, not hidden.
 */
class VaultRepository(
    private val store: VaultFileStore,
    private val codec: VaultCodec = VaultCodec(),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VaultState>(
        if (store.exists()) VaultState.Locked else VaultState.Uninitialized,
    )
    val state: StateFlow<VaultState> = _state.asStateFlow()

    // Memory-only unlocked material.
    private var encryptedFile: EncryptedVaultFile? = null
    private var dek: SecretKey? = null
    private var document: VaultDocument? = null

    fun isInitialized(): Boolean = store.exists()

    val isUnlocked: Boolean get() = document != null

    /** Re-checks disk; useful after an external reset. No-op while unlocked. */
    fun refreshLockState() {
        if (document != null) return
        _state.value = if (store.exists()) VaultState.Locked else VaultState.Uninitialized
    }

    // -------------------------------------------------------------------------
    // Creation & unlock
    // -------------------------------------------------------------------------

    suspend fun createVault(masterPassword: CharArray, rawAnswers: List<String>): Result<Unit> =
        mutex.withLock {
            runCatching {
                check(!store.exists()) { "A vault already exists on this device." }
                val fresh = VaultDocument()
                val file = codec.createVault(masterPassword, rawAnswers, fresh)
                store.write(file)
                adopt(file, codec.unlockWithMaster(file, masterPassword))
            }
        }

    /**
     * Rebuilds the on-disk vault around [document] under a fresh master password
     * and fresh security answers, then unlocks it. Used by encrypted-backup
     * restore (§ backup/restore). Works whether or not a vault already exists:
     * [com.example.offlinepasswordwallet.data.storage.VaultFileStore.write] does
     * an atomic replace. Callers that are overwriting an existing vault MUST have
     * taken an explicit user confirmation first.
     */
    suspend fun replaceVaultWithDocument(
        masterPassword: CharArray,
        rawAnswers: List<String>,
        document: VaultDocument,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            val file = codec.createVault(masterPassword, rawAnswers, document)
            store.write(file)
            adopt(file, codec.unlockWithMaster(file, masterPassword))
        }
    }

    suspend fun unlockWithMaster(masterPassword: CharArray): Result<Unit> = mutex.withLock {
        runCatching {
            val file = store.read()
            adopt(file, codec.unlockWithMaster(file, masterPassword))
        }
    }

    suspend fun unlockWithRecoveryAnswers(rawAnswers: List<String>): Result<Unit> = mutex.withLock {
        runCatching {
            val file = store.read()
            adopt(file, codec.unlockWithRecovery(file, rawAnswers))
        }
    }

    /** Unlock using a DEK already recovered through Android Keystore / biometrics. */
    suspend fun unlockWithDek(recoveredDek: SecretKey): Result<Unit> = mutex.withLock {
        runCatching {
            val file = store.read()
            adopt(file, codec.unlockWithDek(file, recoveredDek))
        }
    }

    fun lock() {
        wipeSecrets()
        _state.value = if (store.exists()) VaultState.Locked else VaultState.Uninitialized
    }

    // -------------------------------------------------------------------------
    // Access for security subsystems (biometric enable, change flows)
    // -------------------------------------------------------------------------

    /** The live DEK, only while unlocked. Used to wrap it for biometric login. */
    fun currentDek(): SecretKey? = dek

    fun currentDocument(): VaultDocument? = document

    fun encryptedFileSnapshot(): EncryptedVaultFile? = encryptedFile

    // -------------------------------------------------------------------------
    // Master password / recovery / security answers
    // -------------------------------------------------------------------------

    /** Verifies [current] by attempting a decrypt, then re-wraps the DEK under [new]. */
    suspend fun changeMasterPassword(current: CharArray, new: CharArray): Result<Unit> =
        mutex.withLock {
            runCatching {
                val file = encryptedFile ?: store.read()
                // Verify current password (throws AeadDecryptionException if wrong).
                codec.unlockWithMaster(file, current)
                val liveDek = dek ?: error("Vault must be unlocked to change the master password.")
                val updated = codec.rewrapMaster(file, liveDek, new)
                store.write(updated)
                encryptedFile = updated
            }
        }

    /**
     * Sets a NEW master password without checking an old one. Only valid right
     * after a successful recovery unlock ([unlockWithRecoveryAnswers]); the old
     * password is never revealed or required.
     */
    suspend fun setMasterPasswordAfterRecovery(new: CharArray): Result<Unit> = mutex.withLock {
        runCatching {
            val file = encryptedFile ?: error("Recovery unlock required first.")
            val liveDek = dek ?: error("Recovery unlock required first.")
            val updated = codec.rewrapMaster(file, liveDek, new)
            store.write(updated)
            encryptedFile = updated
        }
    }

    suspend fun changeSecurityAnswers(
        currentMaster: CharArray,
        newRawAnswers: List<String>,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            val file = encryptedFile ?: store.read()
            codec.unlockWithMaster(file, currentMaster) // verify master
            val liveDek = dek ?: error("Vault must be unlocked.")
            val updated = codec.rewrapRecovery(file, liveDek, newRawAnswers)
            store.write(updated)
            encryptedFile = updated
        }
    }

    // -------------------------------------------------------------------------
    // Entry mutations
    // -------------------------------------------------------------------------

    suspend fun upsertEntry(entry: VaultEntry): Result<Unit> = mutate { doc ->
        val stamped = entry.copy(updatedAtEpochMillis = System.currentTimeMillis())
        val idx = doc.entries.indexOfFirst { it.id == entry.id }
        val entries = if (idx >= 0) {
            doc.entries.toMutableList().apply { this[idx] = stamped }
        } else {
            doc.entries + stamped
        }
        doc.copy(entries = entries)
    }

    suspend fun deleteEntry(id: String): Result<Unit> = mutate { doc ->
        doc.copy(entries = doc.entries.filterNot { it.id == id })
    }

    suspend fun duplicateEntry(id: String): Result<Unit> = mutate { doc ->
        val original = doc.entries.firstOrNull { it.id == id } ?: return@mutate doc
        val now = System.currentTimeMillis()
        val title = original.value("Title")
        val copy = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            fields = original.fields.map {
                if (it.name.equals("Title", ignoreCase = true) && !title.isNullOrBlank()) {
                    it.copy(value = "$title (copy)")
                } else it
            },
        )
        doc.copy(entries = doc.entries + copy)
    }

    suspend fun importEntries(imported: List<VaultEntry>, mode: ImportMode): Result<Unit> =
        mutate { doc ->
            when (mode) {
                ImportMode.ADD -> doc.copy(entries = doc.entries + imported)
                ImportMode.REPLACE -> doc.copy(entries = imported)
            }
        }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private suspend fun mutate(transform: (VaultDocument) -> VaultDocument): Result<Unit> =
        mutex.withLock {
            runCatching {
                val current = document ?: error("Vault is locked.")
                val file = encryptedFile ?: error("Vault is locked.")
                val liveDek = dek ?: error("Vault is locked.")
                val next = transform(current)
                val updated = codec.updateDocument(file, liveDek, next)
                store.write(updated)
                encryptedFile = updated
                document = next
                _state.value = VaultState.Unlocked(next.entries)
            }
        }

    private fun adopt(file: EncryptedVaultFile, unlocked: UnlockedVault) {
        encryptedFile = file
        dek = unlocked.dek
        document = unlocked.document
        _state.value = VaultState.Unlocked(unlocked.document.entries)
    }

    private fun wipeSecrets() {
        runCatching { dek?.encoded?.fill(0) }
        dek = null
        document = null
        encryptedFile = null
    }
}

/** Convenience for the UI: friendly message for any failure. */
fun Throwable.toUserMessage(): String = when (this) {
    is VaultCryptoException -> message ?: "A cryptographic error occurred."
    else -> message ?: "Something went wrong."
}
