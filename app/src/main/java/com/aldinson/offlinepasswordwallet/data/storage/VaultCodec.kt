package com.aldinson.offlinepasswordwallet.data.storage

import com.aldinson.offlinepasswordwallet.crypto.Base64Util
import com.aldinson.offlinepasswordwallet.crypto.CryptoConstants
import com.aldinson.offlinepasswordwallet.crypto.EncryptedBlob
import com.aldinson.offlinepasswordwallet.crypto.SecurityAnswers
import com.aldinson.offlinepasswordwallet.crypto.UnlockedVault
import com.aldinson.offlinepasswordwallet.crypto.VaultCrypto
import com.aldinson.offlinepasswordwallet.crypto.VaultFormatException
import com.aldinson.offlinepasswordwallet.data.model.EncryptedVaultFile
import com.aldinson.offlinepasswordwallet.data.model.KdfParamsDto
import com.aldinson.offlinepasswordwallet.data.model.VaultDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Turns an in-memory [VaultDocument] + secrets into an [EncryptedVaultFile] and
 * back, implementing the wrapped-DEK scheme described in [CryptoConstants].
 *
 * Pure and I/O-free: takes/returns objects, never touches the filesystem. That is
 * [VaultFileStore]'s job.
 *
 * **Associated data (format v2+).** The payload's AES-GCM is authenticated over
 * `opw-vault-payload|v<version>|<vaultId>|<revision>`. `revision` increments on
 * every persisted change, so a `payload` blob captured from an earlier version of
 * the same file no longer authenticates against the current header — the silent
 * rollback described in the review is now a hard decryption failure. The AAD
 * deliberately excludes the salts and wrapped keys so that changing the master
 * password or the security answers does not invalidate the payload.
 *
 * v1 files (no AAD, no revision) are still readable and are upgraded to v2 on the
 * next write.
 */
class VaultCodec(
    private val crypto: VaultCrypto = VaultCrypto(),
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // -------------------------------------------------------------------------
    // Creation
    // -------------------------------------------------------------------------

    /**
     * Builds a brand-new encrypted vault. Generates a fresh DEK, wraps it under
     * both the master password and the recovery answers, and seals [document].
     * The caller owns [masterPassword] / [rawAnswers] and should zero them after.
     */
    fun createVault(
        masterPassword: CharArray,
        rawAnswers: List<String>,
        document: VaultDocument,
    ): EncryptedVaultFile {
        val dek = crypto.generateDek()
        val masterSalt = crypto.randomSalt()
        val recoverySalt = crypto.randomSalt()

        val wrappedMaster = wrapUnderMaster(dek, masterPassword, masterSalt)
        val wrappedRecovery = wrapUnderRecovery(dek, rawAnswers, recoverySalt)

        val vaultId = UUID.randomUUID().toString()
        val revision = 1L
        val payload = sealDocument(
            dek = dek,
            document = document,
            aad = payloadAad(CryptoConstants.VAULT_FORMAT_VERSION, vaultId, revision),
        )

        val now = System.currentTimeMillis()
        return EncryptedVaultFile(
            formatVersion = CryptoConstants.VAULT_FORMAT_VERSION,
            kdf = currentKdfParams(),
            masterSaltB64 = Base64Util.encode(masterSalt),
            recoverySaltB64 = Base64Util.encode(recoverySalt),
            wrappedKeyMaster = wrappedMaster.toDto(),
            wrappedKeyRecovery = wrappedRecovery.toDto(),
            payload = payload.toDto(),
            securityQuestions = SecurityAnswers.QUESTIONS,
            vaultId = vaultId,
            createdAtEpochMillis = now,
            modifiedAtEpochMillis = now,
            revision = revision,
        )
    }

    // -------------------------------------------------------------------------
    // Unlock
    // -------------------------------------------------------------------------

    fun unlockWithMaster(file: EncryptedVaultFile, masterPassword: CharArray): UnlockedVault {
        validate(file)
        val salt = Base64Util.decode(file.masterSaltB64)
        val kek = crypto.deriveKey(
            masterPassword,
            salt,
            file.kdf.masterIterations,
            file.kdf.keyLengthBits,
        )
        try {
            val dek = crypto.unwrapDek(kek, EncryptedBlob.fromDto(file.wrappedKeyMaster))
            return UnlockedVault(dek, openDocument(file, dek))
        } finally {
            zero(kek)
        }
    }

    fun unlockWithRecovery(file: EncryptedVaultFile, rawAnswers: List<String>): UnlockedVault {
        validate(file)
        val salt = Base64Util.decode(file.recoverySaltB64)
        val passphrase = SecurityAnswers.toPassphrase(rawAnswers)
        val kek = try {
            crypto.deriveKey(passphrase, salt, file.kdf.recoveryIterations, file.kdf.keyLengthBits)
        } finally {
            passphrase.fill(' ')
        }
        try {
            val dek = crypto.unwrapDek(kek, EncryptedBlob.fromDto(file.wrappedKeyRecovery))
            return UnlockedVault(dek, openDocument(file, dek))
        } finally {
            zero(kek)
        }
    }

    /** Unlock using a DEK already recovered via Android Keystore / biometrics. */
    fun unlockWithDek(file: EncryptedVaultFile, dek: SecretKey): UnlockedVault {
        validate(file)
        return UnlockedVault(dek, openDocument(file, dek))
    }

    // -------------------------------------------------------------------------
    // Mutation (re-seal / re-wrap) — always returns a NEW file object
    // -------------------------------------------------------------------------

    /**
     * Re-encrypts the payload with a fresh IV after an in-memory edit, bumps the
     * revision, and upgrades the file to the current format version.
     */
    fun updateDocument(
        file: EncryptedVaultFile,
        dek: SecretKey,
        document: VaultDocument,
    ): EncryptedVaultFile {
        val nextRevision = file.revision + 1
        val payload = sealDocument(
            dek = dek,
            document = document,
            aad = payloadAad(CryptoConstants.VAULT_FORMAT_VERSION, file.vaultId, nextRevision),
        )
        return file.copy(
            formatVersion = CryptoConstants.VAULT_FORMAT_VERSION,
            payload = payload.toDto(),
            revision = nextRevision,
            modifiedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Re-wraps the DEK under a new master password (new salt). Payload untouched.
     *
     * The master half of [EncryptedVaultFile.kdf] is refreshed to this build's
     * constants at the same time, so the recorded parameters always match the
     * wrapper that was just produced. (Bumping the iteration count in a future
     * release must not silently invalidate an existing wrapper.)
     */
    fun rewrapMaster(
        file: EncryptedVaultFile,
        dek: SecretKey,
        newMasterPassword: CharArray,
    ): EncryptedVaultFile {
        val newSalt = crypto.randomSalt()
        val wrapped = wrapUnderMaster(dek, newMasterPassword, newSalt)
        return file.copy(
            masterSaltB64 = Base64Util.encode(newSalt),
            wrappedKeyMaster = wrapped.toDto(),
            kdf = file.kdf.copy(
                algorithm = CryptoConstants.PBKDF2_ALGORITHM,
                masterIterations = CryptoConstants.PBKDF2_ITERATIONS_MASTER,
                keyLengthBits = CryptoConstants.PBKDF2_KEY_LENGTH_BITS,
            ),
            modifiedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /** Re-wraps the DEK under new recovery answers (new salt). Payload untouched. */
    fun rewrapRecovery(
        file: EncryptedVaultFile,
        dek: SecretKey,
        newRawAnswers: List<String>,
    ): EncryptedVaultFile {
        val newSalt = crypto.randomSalt()
        val wrapped = wrapUnderRecovery(dek, newRawAnswers, newSalt)
        return file.copy(
            recoverySaltB64 = Base64Util.encode(newSalt),
            wrappedKeyRecovery = wrapped.toDto(),
            kdf = file.kdf.copy(
                algorithm = CryptoConstants.PBKDF2_ALGORITHM,
                recoveryIterations = CryptoConstants.PBKDF2_ITERATIONS_RECOVERY,
                keyLengthBits = CryptoConstants.PBKDF2_KEY_LENGTH_BITS,
            ),
            modifiedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    // -------------------------------------------------------------------------
    // (De)serialization of the file envelope
    // -------------------------------------------------------------------------

    fun encodeToBytes(file: EncryptedVaultFile): ByteArray =
        json.encodeToString(EncryptedVaultFile.serializer(), file).toByteArray(Charsets.UTF_8)

    fun decodeFromBytes(bytes: ByteArray): EncryptedVaultFile {
        val text = bytes.toString(Charsets.UTF_8)
        val file = try {
            json.decodeFromString(EncryptedVaultFile.serializer(), text)
        } catch (e: SerializationException) {
            throw VaultFormatException("Vault file is not readable or not in a supported format.", e)
        }
        validate(file)
        return file
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    /**
     * Rejects a header whose parameters are outside the accepted window before any
     * of them reach the KDF. Without this, a tampered/hostile file could pin the
     * CPU for hours with an absurd iteration count (a denial of service that locks
     * the owner out) or silently drop the KDF below the documented floor.
     */
    private fun validate(file: EncryptedVaultFile) {
        if (file.formatVersion < CryptoConstants.MIN_SUPPORTED_VAULT_FORMAT_VERSION ||
            file.formatVersion > CryptoConstants.VAULT_FORMAT_VERSION
        ) {
            throw VaultFormatException(
                "Vault format version ${file.formatVersion} is not supported by this app version.",
            )
        }
        if (file.kdf.algorithm != CryptoConstants.PBKDF2_ALGORITHM) {
            throw VaultFormatException("Unsupported key-derivation algorithm in the vault file.")
        }
        requireIterations(file.kdf.masterIterations)
        requireIterations(file.kdf.recoveryIterations)
        if (file.kdf.keyLengthBits !in CryptoConstants.ACCEPTED_KDF_KEY_LENGTH_BITS) {
            throw VaultFormatException("Unsupported key length in the vault file.")
        }
        requireSalt(file.masterSaltB64)
        requireSalt(file.recoverySaltB64)
        if (file.revision < 1L) {
            throw VaultFormatException("Vault file has an invalid revision counter.")
        }
        if (file.vaultId.isBlank()) {
            throw VaultFormatException("Vault file is missing its identifier.")
        }
    }

    private fun requireIterations(iterations: Int) {
        if (iterations < CryptoConstants.MIN_ACCEPTED_KDF_ITERATIONS ||
            iterations > CryptoConstants.MAX_ACCEPTED_KDF_ITERATIONS
        ) {
            throw VaultFormatException(
                "Vault file declares an out-of-range key-derivation cost and was not opened.",
            )
        }
    }

    private fun requireSalt(saltB64: String) {
        val salt = try {
            Base64Util.decode(saltB64)
        } catch (e: IllegalArgumentException) {
            throw VaultFormatException("Vault file contains a malformed salt.", e)
        }
        if (salt.isEmpty() || salt.size > CryptoConstants.MAX_SALT_LENGTH_BYTES) {
            throw VaultFormatException("Vault file contains a malformed salt.")
        }
    }

    /** Associated data binding a payload to this vault identity + write counter. */
    private fun payloadAad(formatVersion: Int, vaultId: String, revision: Long): ByteArray =
        "opw-vault-payload|v$formatVersion|$vaultId|$revision".toByteArray(Charsets.UTF_8)

    private fun currentKdfParams() = KdfParamsDto(
        algorithm = CryptoConstants.PBKDF2_ALGORITHM,
        masterIterations = CryptoConstants.PBKDF2_ITERATIONS_MASTER,
        recoveryIterations = CryptoConstants.PBKDF2_ITERATIONS_RECOVERY,
        keyLengthBits = CryptoConstants.PBKDF2_KEY_LENGTH_BITS,
    )

    private fun wrapUnderMaster(
        dek: SecretKey,
        masterPassword: CharArray,
        salt: ByteArray,
    ): EncryptedBlob {
        val kek = crypto.deriveKey(masterPassword, salt, CryptoConstants.PBKDF2_ITERATIONS_MASTER)
        try {
            return crypto.wrapDek(kek, dek)
        } finally {
            zero(kek)
        }
    }

    private fun wrapUnderRecovery(
        dek: SecretKey,
        rawAnswers: List<String>,
        salt: ByteArray,
    ): EncryptedBlob {
        val passphrase = SecurityAnswers.toPassphrase(rawAnswers)
        val kek = try {
            crypto.deriveKey(passphrase, salt, CryptoConstants.PBKDF2_ITERATIONS_RECOVERY)
        } finally {
            passphrase.fill(' ')
        }
        try {
            return crypto.wrapDek(kek, dek)
        } finally {
            zero(kek)
        }
    }

    private fun sealDocument(
        dek: SecretKey,
        document: VaultDocument,
        aad: ByteArray,
    ): EncryptedBlob {
        val plaintext = json.encodeToString(VaultDocument.serializer(), document)
            .toByteArray(Charsets.UTF_8)
        try {
            return crypto.encrypt(dek, plaintext, associatedData = aad)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun openDocument(file: EncryptedVaultFile, dek: SecretKey): VaultDocument {
        // v1 payloads were sealed without associated data.
        val aad = if (file.formatVersion >= CryptoConstants.FIRST_AAD_VAULT_FORMAT_VERSION) {
            payloadAad(file.formatVersion, file.vaultId, file.revision)
        } else {
            null
        }
        val plaintext = crypto.decrypt(dek, EncryptedBlob.fromDto(file.payload), associatedData = aad)
        try {
            return json.decodeFromString(
                VaultDocument.serializer(),
                plaintext.toString(Charsets.UTF_8),
            )
        } catch (e: SerializationException) {
            throw VaultFormatException("Decrypted vault content is not valid.", e)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun zero(key: SecretKey) {
        // Best-effort: SecretKeySpec copies its bytes, but if the provider exposes
        // the backing array we clear it. Documented limitation in README.
        runCatching { key.encoded?.fill(0) }
    }
}
