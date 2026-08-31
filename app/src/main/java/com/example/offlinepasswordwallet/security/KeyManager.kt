package com.example.offlinepasswordwallet.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.example.offlinepasswordwallet.crypto.Base64Util
import com.example.offlinepasswordwallet.crypto.CryptoConstants
import com.example.offlinepasswordwallet.crypto.EncryptedBlob
import com.example.offlinepasswordwallet.data.model.EncryptedBlobDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
     */
    fun finishEnable(cipher: Cipher, dek: SecretKey) {
        val dekBytes = dek.encoded
        try {
            val ct = cipher.doFinal(dekBytes)
            val blob = EncryptedBlob(iv = cipher.iv, ciphertext = ct)
            blobFile.parentFile?.mkdirs()
            blobFile.writeText(json.encodeToString(BiometricBlob.serializer(), BiometricBlob(blob.toDto())))
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
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricKeyInvalidatedException(e)
        }
    }

    /**
     * Uses the biometric-authorized [cipher] to unwrap and return the DEK.
     *
     * @throws BiometricKeyInvalidatedException on key invalidation surfaced late.
     */
    fun unwrapDekAfterAuth(cipher: Cipher): SecretKey {
        val blob = readBlob()
        val dekBytes = try {
            cipher.doFinal(blob.ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw BiometricKeyInvalidatedException(e)
        }
        try {
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
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private fun readBlob(): EncryptedBlob {
        val text = blobFile.readText()
        val parsed = json.decodeFromString(BiometricBlob.serializer(), text)
        return EncryptedBlob.fromDto(parsed.wrappedDek)
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
