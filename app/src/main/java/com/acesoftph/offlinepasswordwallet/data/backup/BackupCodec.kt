package com.acesoftph.offlinepasswordwallet.data.backup

import com.acesoftph.offlinepasswordwallet.crypto.AeadDecryptionException
import com.acesoftph.offlinepasswordwallet.crypto.Base64Util
import com.acesoftph.offlinepasswordwallet.crypto.BackupDecryptionException
import com.acesoftph.offlinepasswordwallet.crypto.BackupFormatException
import com.acesoftph.offlinepasswordwallet.crypto.CryptoConstants
import com.acesoftph.offlinepasswordwallet.crypto.EncryptedBlob
import com.acesoftph.offlinepasswordwallet.crypto.VaultCrypto
import com.acesoftph.offlinepasswordwallet.data.model.VaultDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.crypto.SecretKey

/**
 * Encodes / decodes the portable [EncryptedBackupFile].
 *
 * Format: a small JSON envelope whose [EncryptedBackupFile.payload] is
 * AES-256-GCM ciphertext of the serialized [VaultDocument], under a key derived
 * from the backup passphrase with PBKDF2-HMAC-SHA256 (same 600 000 iterations as
 * the master KDF) and a fresh random 16-byte salt. A fresh random 96-bit IV is
 * generated per export by [VaultCrypto.encrypt].
 *
 * Pure and I/O-free. The caller owns the passphrase [CharArray] and should zero
 * it afterwards.
 */
class BackupCodec(
    private val crypto: VaultCrypto = VaultCrypto(),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun create(
        passphrase: CharArray,
        document: VaultDocument,
        appVersionName: String,
    ): ByteArray {
        val salt = crypto.randomSalt()
        val key = crypto.deriveKey(passphrase, salt, ITERATIONS)
        try {
            val plaintext = json.encodeToString(VaultDocument.serializer(), document)
                .toByteArray(Charsets.UTF_8)
            val blob = try {
                crypto.encrypt(key, plaintext)
            } finally {
                plaintext.fill(0)
            }
            val file = EncryptedBackupFile(
                magic = MAGIC,
                formatVersion = FORMAT_VERSION,
                kdf = BackupKdfDto(CryptoConstants.PBKDF2_ALGORITHM, ITERATIONS, 256),
                saltB64 = Base64Util.encode(salt),
                payload = blob.toDto(),
                entryCount = document.entries.size,
                createdAtEpochMillis = System.currentTimeMillis(),
                appVersionName = appVersionName,
            )
            return json.encodeToString(EncryptedBackupFile.serializer(), file)
                .toByteArray(Charsets.UTF_8)
        } finally {
            zero(key)
        }
    }

    fun decode(bytes: ByteArray): EncryptedBackupFile {
        val file = try {
            json.decodeFromString(EncryptedBackupFile.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupFormatException("This file is not an Offline Password Wallet backup.", e)
        }
        if (file.magic != MAGIC) {
            throw BackupFormatException("This file is not an Offline Password Wallet backup.")
        }
        if (file.formatVersion != FORMAT_VERSION) {
            throw BackupFormatException(
                "Backup format version ${file.formatVersion} is not supported by this app version.",
            )
        }
        // The KDF cost comes out of an untrusted file. Honouring an absurd value
        // would pin the CPU for hours (a denial of service that locks the owner
        // out of their own backup); honouring a tiny one would silently weaken the
        // KDF below the documented floor.
        if (file.kdf.algorithm != CryptoConstants.PBKDF2_ALGORITHM) {
            throw BackupFormatException("Unsupported key-derivation algorithm in the backup file.")
        }
        if (file.kdf.iterations < CryptoConstants.MIN_ACCEPTED_KDF_ITERATIONS ||
            file.kdf.iterations > CryptoConstants.MAX_ACCEPTED_KDF_ITERATIONS
        ) {
            throw BackupFormatException(
                "Backup file declares an out-of-range key-derivation cost and was not opened.",
            )
        }
        if (file.kdf.keyLengthBits !in CryptoConstants.ACCEPTED_KDF_KEY_LENGTH_BITS) {
            throw BackupFormatException("Unsupported key length in the backup file.")
        }
        val salt = try {
            Base64Util.decode(file.saltB64)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Backup file contains a malformed salt.", e)
        }
        if (salt.isEmpty() || salt.size > CryptoConstants.MAX_SALT_LENGTH_BYTES) {
            throw BackupFormatException("Backup file contains a malformed salt.")
        }
        return file
    }

    /**
     * @throws BackupDecryptionException if the passphrase is wrong or the file
     *         was tampered with / corrupted.
     */
    fun open(file: EncryptedBackupFile, passphrase: CharArray): VaultDocument {
        // decode() has already bounded these; re-check so a hand-built
        // EncryptedBackupFile cannot bypass the guard by calling open() directly.
        if (file.kdf.iterations < CryptoConstants.MIN_ACCEPTED_KDF_ITERATIONS ||
            file.kdf.iterations > CryptoConstants.MAX_ACCEPTED_KDF_ITERATIONS
        ) {
            throw BackupFormatException("Backup file declares an out-of-range key-derivation cost.")
        }
        val salt = Base64Util.decode(file.saltB64)
        val key = crypto.deriveKey(passphrase, salt, file.kdf.iterations, file.kdf.keyLengthBits)
        try {
            val plaintext = try {
                crypto.decrypt(key, EncryptedBlob.fromDto(file.payload))
            } catch (e: AeadDecryptionException) {
                throw BackupDecryptionException(e)
            }
            try {
                return json.decodeFromString(
                    VaultDocument.serializer(),
                    plaintext.toString(Charsets.UTF_8),
                )
            } catch (e: SerializationException) {
                throw BackupDecryptionException(e)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            zero(key)
        }
    }

    private fun zero(key: SecretKey) {
        runCatching { key.encoded?.fill(0) }
    }

    companion object {
        const val MAGIC = "OPW-ENCRYPTED-BACKUP"
        const val FORMAT_VERSION = 1
        const val FILE_EXTENSION = "opwbackup"
        val ITERATIONS = CryptoConstants.PBKDF2_ITERATIONS_MASTER
    }
}
