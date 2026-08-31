package com.example.offlinepasswordwallet.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** What the device can currently do with class-3 (strong) biometrics. */
enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NONE_ENROLLED,
    SECURITY_UPDATE_REQUIRED,
    UNKNOWN,
}

/**
 * Thin wrapper over AndroidX [BiometricPrompt] (§19). We only ever use
 * `BIOMETRIC_STRONG` (class 3) because a [Cipher] `CryptoObject` requires it, and
 * class-3 auth is what gates the Keystore key in [KeyManager].
 *
 * No custom fingerprint/face code exists anywhere in this app; the OS does all
 * matching and never exposes raw biometric data to us.
 */
object BiometricAuthenticator {

    fun availability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.SECURITY_UPDATE_REQUIRED
            else -> BiometricAvailability.UNKNOWN
        }
    }

    fun isAvailable(context: Context): Boolean =
        availability(context) == BiometricAvailability.AVAILABLE

    /**
     * Shows the system biometric sheet bound to [cipher].
     *
     * @param onSuccess receives the authenticated [Cipher] (from the CryptoObject).
     * @param onError terminal errors (lockout, user cancel, no biometrics, …).
     * @param onFailed a single non-matching attempt; the prompt stays open.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (code: Int, message: String) -> Unit,
        onFailed: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher != null) {
                        onSuccess(authenticatedCipher)
                    } else {
                        onError(-1, "Biometric result did not include a crypto object.")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailed()
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use master password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
