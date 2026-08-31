package com.example.offlinepasswordwallet.crypto

/** Base type for every recoverable cryptographic failure surfaced to the UI. */
sealed class VaultCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Authenticated decryption failed: the GCM tag did not verify. This means EITHER
 * the key was wrong (e.g. wrong master password / wrong recovery answers) OR the
 * ciphertext/associated data was modified or corrupted. We cannot tell which, and
 * we never guess — the UI shows a single combined message.
 */
class AeadDecryptionException(cause: Throwable? = null) :
    VaultCryptoException(
        "Unable to unlock the password vault. The vault file may be corrupted or the " +
            "password may be incorrect.",
        cause,
    )

/** The vault file could not be parsed at all (truncated / not our format). */
class VaultFormatException(message: String, cause: Throwable? = null) :
    VaultCryptoException(message, cause)

/** An unexpected platform crypto error (missing algorithm, provider issue). */
class CryptoUnavailableException(message: String, cause: Throwable? = null) :
    VaultCryptoException(message, cause)

/**
 * The vault file on disk is older than the last write this device recorded — it
 * was replaced with a stale copy (a restored file backup, a sync conflict, or
 * deliberate tampering). Opening it would silently resurrect deleted or rotated
 * credentials, so we refuse and tell the user instead.
 */
class VaultRollbackException(val storedRevision: Long, val fileRevision: Long) :
    VaultCryptoException(
        "The vault file on this device is older than the last saved version " +
            "(found revision $fileRevision, expected at least $storedRevision). It may have " +
            "been replaced with an out-of-date copy. Restore an encrypted backup instead of " +
            "using this file.",
    )

/** The selected file is not an Offline Password Wallet encrypted backup. */
class BackupFormatException(message: String, cause: Throwable? = null) :
    VaultCryptoException(message, cause)

/**
 * The encrypted backup could not be decrypted/authenticated: either the backup
 * passphrase is wrong, or the file was modified/corrupted.
 */
class BackupDecryptionException(cause: Throwable? = null) :
    VaultCryptoException(
        "Could not open the backup. The backup passphrase may be incorrect, or the " +
            "backup file may be corrupted.",
        cause,
    )
