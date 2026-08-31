package com.example.offlinepasswordwallet.crypto

import java.security.GeneralSecurityException
import java.security.spec.KeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level, UI-agnostic cryptographic primitives for the vault.
 *
 * Responsibilities:
 *  - AES-256-GCM authenticated encryption / decryption with a fresh random 96-bit
 *    IV per operation ([encrypt] always generates the IV; callers can never
 *    accidentally reuse one).
 *  - PBKDF2-HMAC-SHA256 key derivation from a password / passphrase.
 *  - Generating the random 256-bit Data Encryption Key (DEK) and random salts.
 *  - Wrapping / unwrapping the DEK under a derived key.
 *
 * This class holds no state and performs no I/O. Android Keystore handling lives
 * in [com.example.offlinepasswordwallet.security.KeyManager]; this type only deals
 * with in-process [SecretKey]s so it is fully unit-testable on a plain JVM.
 *
 * NOTE on `String` vs `CharArray`: password/answer inputs are taken as
 * [CharArray] so the caller can zero them after use. The JVM still cannot
 * guarantee no copies survive in memory (see README "Memory security"), but this
 * avoids interning secrets in the String pool and shortens their lifetime.
 */
class VaultCrypto {

    // ---------------------------------------------------------------------------
    // AES-256-GCM
    // ---------------------------------------------------------------------------

    /**
     * Encrypts [plaintext] under [key] with AES-256-GCM. A cryptographically
     * random 96-bit IV is generated here for every call. [associatedData], if
     * given, is authenticated but not encrypted.
     */
    fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
        associatedData: ByteArray? = null,
    ): EncryptedBlob {
        try {
            val iv = SecureRandomProvider.nextBytes(CryptoConstants.GCM_IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, iv),
            )
            if (associatedData != null) cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            return EncryptedBlob(iv = iv, ciphertext = ciphertext)
        } catch (e: GeneralSecurityException) {
            throw CryptoUnavailableException("AES-GCM encryption failed", e)
        }
    }

    /**
     * Decrypts and authenticates [blob] under [key].
     *
     * @throws AeadDecryptionException if the GCM tag does not verify (wrong key
     *         OR tampered/corrupted data).
     */
    fun decrypt(
        key: SecretKey,
        blob: EncryptedBlob,
        associatedData: ByteArray? = null,
    ): ByteArray {
        try {
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, blob.iv),
            )
            if (associatedData != null) cipher.updateAAD(associatedData)
            return cipher.doFinal(blob.ciphertext)
        } catch (e: AEADBadTagException) {
            throw AeadDecryptionException(e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw AeadDecryptionException(e)
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            throw AeadDecryptionException(e)
        } catch (e: GeneralSecurityException) {
            throw CryptoUnavailableException("AES-GCM decryption failed", e)
        }
    }

    // ---------------------------------------------------------------------------
    // PBKDF2 key derivation
    // ---------------------------------------------------------------------------

    fun deriveKey(
        secret: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int = CryptoConstants.PBKDF2_KEY_LENGTH_BITS,
    ): SecretKey {
        var spec: PBEKeySpec? = null
        try {
            spec = PBEKeySpec(secret, salt, iterations, keyLengthBits)
            val factory = SecretKeyFactory.getInstance(CryptoConstants.PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec as KeySpec).encoded
            try {
                return SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM)
            } finally {
                keyBytes.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoUnavailableException("PBKDF2 key derivation failed", e)
        } finally {
            spec?.clearPassword()
        }
    }

    // ---------------------------------------------------------------------------
    // DEK + salt generation
    // ---------------------------------------------------------------------------

    /** Fresh random 256-bit AES key used to seal the vault payload. */
    fun generateDek(): SecretKey =
        SecretKeySpec(
            SecureRandomProvider.nextBytes(CryptoConstants.DEK_LENGTH_BYTES),
            CryptoConstants.AES_ALGORITHM,
        )

    fun randomSalt(): ByteArray =
        SecureRandomProvider.nextBytes(CryptoConstants.SALT_LENGTH_BYTES)

    // ---------------------------------------------------------------------------
    // DEK wrapping helpers
    // ---------------------------------------------------------------------------

    /** Wraps (encrypts) [dek] under [wrappingKey]. */
    fun wrapDek(wrappingKey: SecretKey, dek: SecretKey): EncryptedBlob {
        val dekBytes = dek.encoded
        try {
            return encrypt(wrappingKey, dekBytes)
        } finally {
            dekBytes.fill(0)
        }
    }

    /**
     * Unwraps [wrapped] under [wrappingKey], returning the DEK.
     *
     * @throws AeadDecryptionException if [wrappingKey] is wrong or [wrapped] is
     *         corrupted.
     */
    fun unwrapDek(wrappingKey: SecretKey, wrapped: EncryptedBlob): SecretKey {
        val dekBytes = decrypt(wrappingKey, wrapped)
        try {
            return SecretKeySpec(dekBytes, CryptoConstants.AES_ALGORITHM)
        } finally {
            dekBytes.fill(0)
        }
    }
}
