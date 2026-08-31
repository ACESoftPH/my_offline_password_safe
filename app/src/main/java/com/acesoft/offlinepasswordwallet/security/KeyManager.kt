package com.acesoft.offlinepasswordwallet.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.acesoft.offlinepasswordwallet.crypto.Base64Util
import com.acesoft.offlinepasswordwallet.crypto.CryptoConstants
import com.acesoft.offlinepasswordwallet.crypto.EncryptedBlob
import com.acesoft.offlinepasswordwallet.data.model.EncryptedBlobDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Biometric key management (§19–§20).
 *
 * Scheme: an AES-256-GCM key is generated **inside the Android Keystore** with
 * `setUserAuthenticationRequired(true)` and
 * `setInvalidatedByBiometricEnrollment(true)`. That Keystore key is used to wrap
 * the vault DEK; the wrapped blob is stored in app-private storage
 * (`vault/biometric.json`). The raw DEK, the master password, and the security
 * answers are NEVER stored for biometric unlock.
 *
 * Revocation / invalidation is handled by the OS:
 *  - disabling biometric login here deletes the Keystore key + the wrapped blob;
 *  - enrolling a new fingerprint/face, or removing all biometrics, permanently
 *    invalidates the Keystore key -> [unwrapDekAfterAuth] / [getDecryptCipher]
 *    throw [BiometricKeyInvalidatedException] and the app falls back to master
 *    password, offering to re-enable biometrics.
 *
 * The Keystore key is hardware-backed where the device supports it; this class
 * does not require StrongBox but will not fail if it is absent.
 */
class KeyManager(context: Context) {

    private val blobFile = File(File(context.applicationContext.filesDir, "vault"), "biometric.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class BiometricBlob(val wrappedDek: EncryptedBlobDto)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(CryptoConstants.ANDROID_KEYSTORE).apply { load(null) }
    }

    fun isBiometricConfigured(): Boolean = blobFile.isFile && keyExists()

    private fun keyExists(): Boolean =
        runCatching { keyStore.containsAlias(CryptoConstants.BIOMETRIC_KEY_ALIAS) }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // Enabling biometric login
    // -------------------------------------------------------------------------

    /**
     * (Re)creates the Keystore key and returns an ENCRYPT-mode [Cipher] that the
     * caller must unlock with [androidx.biometric.BiometricPrompt] before passing
     * it to [finishEnable]. Any previous biometric key/blob is discarded first.
     */
    fun beginEnable(): Cipher {
        // Generating a key under an existing alias replaces it, so any previously
        // working biometric login is unavoidably invalidated from here on. Callers
        // MUST turn the `biometricEnabled` setting off if the prompt is cancelled
        // or fails, otherwise the UI would claim biometrics are on while no usable
        // wrapped key exists.
        disable() // start clean
        val key = generateKeystoreKey()
        val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Completes enabling: wraps [dek] with the biometric-authenticated [cipher]
     * and persists the blob. Call only after BiometricPrompt reported success for
     * the exact cipher returned by [beginEnable].
     *
     * The blob is written through a temp file + fsync + atomic rename so a crash
     * mid-write can never leave a half-written `biometric.json` that parses but
     * fails to decrypt.
     */
    fun finishEnable(cipher: Cipher, dek: SecretKey) {
        val dekBytes = dek.encoded
        try {
            val ct = cipher.doFinal(dekBytes)
            val blob = EncryptedBlob(iv = cipher.iv, ciphertext = ct)
            val text = json.encodeToString(BiometricBlob.serializer(), BiometricBlob(blob.toDto()))
            blobFile.parentFile?.mkdirs()
            val temp = File(blobFile.parentFile, blobFile.name + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(blobFile)) {
                temp.copyTo(blobFile, overwrite = true)
                temp.delete()
            }
            restrict(blobFile)
        } finally {
            dekBytes.fill(0)
        }
    }

    // -------------------------------------------------------------------------
    // Unlocking with biometrics
    // -------------------------------------------------------------------------

    /**
     * Returns a DECRYPT-mode [Cipher] initialized with the stored IV, ready to be
     * authorized by BiometricPrompt.
     *
     * @throws BiometricKeyInvalidatedException if the Keystore key is gone or was
     *         invalidated by enrollment/lock-screen changes.
     * @throws BiometricNotConfiguredException if biometric login was never set up.
     */
    fun getDecryptCipher(): Cipher {
        if (!blobFile.isFile) throw BiometricNotConfiguredException()
        val blob = readBlob()
        val key = try {
            keyStore.getKey(CryptoConstants.BIOMETRIC_KEY_ALIAS, null) as? SecretKey
                ?: throw BiometricKeyInvalidatedException(null)
        } catch (e: UnrecoverableKeyException) {
            throw BiometricKeyInvalidatedException(e)
        }
        return try {
            Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, blob.iv),
                )
            }
        } catch (e: BiometricKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException, bad IV length from a corrupt
            // blob, provider errors — all mean "biometric login is unusable".
            throw BiometricKeyInvalidatedException(e)
        }
    }

    /**
     * Uses the biometric-authorized [cipher] to unwrap and return the DEK.
     *
     * Any failure here — key invalidation, a corrupt/rotated `biometric.json`
     * whose GCM tag no longer verifies ([javax.crypto.AEADBadTagException]), a
     * provider error — is reported as [BiometricKeyInvalidatedException] so the
     * caller can disable biometrics and fall back to the master password. Nothing
     * escapes as an unhandled exception: this runs inside the auto-launched
     * unlock coroutine, where an escape would crash the app on every start.
     *
     * @throws BiometricKeyInvalidatedException on any unwrap failure.
     */
    fun unwrapDekAfterAuth(cipher: Cipher): SecretKey {
        val blob = readBlob()
        val dekBytes = try {
            cipher.doFinal(blob.ciphertext)
        } catch (e: Exception) {
            throw BiometricKeyInvalidatedException(e)
        }
        try {
            if (dekBytes.size != CryptoConstants.DEK_LENGTH_BYTES) {
                throw BiometricKeyInvalidatedException(null)
            }
            return SecretKeySpec(dekBytes, CryptoConstants.AES_ALGORITHM)
        } finally {
            dekBytes.fill(0)
        }
    }

    // -------------------------------------------------------------------------
    // Disabling / cleanup
    // -------------------------------------------------------------------------

    /** Fully revokes biometric login: deletes the Keystore key and the blob. */
    fun disable() {
        runCatching {
            if (keyStore.containsAlias(CryptoConstants.BIOMETRIC_KEY_ALIAS)) {
                keyStore.deleteEntry(CryptoConstants.BIOMETRIC_KEY_ALIAS)
            }
        }
        runCatching { blobFile.delete() }
        runCatching { File(blobFile.parentFile, blobFile.name + ".tmp").delete() }
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    /**
     * Reads the wrapped-DEK blob. A missing/unparseable/garbled file is reported
     * as [BiometricKeyInvalidatedException] rather than letting a
     * `SerializationException` or Base64 `IllegalArgumentException` escape.
     */
    private fun readBlob(): EncryptedBlob {
        if (!blobFile.isFile) throw BiometricNotConfiguredException()
        return try {
            val parsed = json.decodeFromString(BiometricBlob.serializer(), blobFile.readText())
            val blob = EncryptedBlob.fromDto(parsed.wrappedDek)
            if (blob.iv.size != CryptoConstants.GCM_IV_LENGTH_BYTES || blob.ciphertext.isEmpty()) {
                throw BiometricKeyInvalidatedException(null)
            }
            blob
        } catch (e: BiometricNotConfiguredException) {
            throw e
        } catch (e: BiometricKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            throw BiometricKeyInvalidatedException(e)
        }
    }

    private fun generateKeystoreKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            CryptoConstants.ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            CryptoConstants.BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(CryptoConstants.DEK_LENGTH_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun restrict(f: File) {
        runCatching {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }
}

class BiometricNotConfiguredException :
    Exception("Biometric login is not set up.")

class BiometricKeyInvalidatedException(cause: Throwable?) :
    Exception(
        "Biometric login is no longer valid (biometrics or the device lock screen " +
            "changed). Unlock with your master password, then re-enable biometric login.",
        cause,
    )
