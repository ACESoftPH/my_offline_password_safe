package com.example.offlinepasswordwallet.settings

/** Auto-lock timeout choices (§21). "Never" is represented by [Long.MAX_VALUE]. */
enum class AutoLockTimeout(val millis: Long, val label: String) {
    SEC_30(30_000L, "30 seconds"),
    MIN_1(60_000L, "1 minute"),
    MIN_2(120_000L, "2 minutes"),
    MIN_5(300_000L, "5 minutes"),
    MIN_10(600_000L, "10 minutes"),
    MIN_30(1_800_000L, "30 minutes"),
    NEVER(Long.MAX_VALUE, "Never");

    companion object {
        val DEFAULT = MIN_5

        fun fromMillis(value: Long): AutoLockTimeout =
            entries.firstOrNull { it.millis == value } ?: DEFAULT
    }
}

/**
 * All user-tunable, NON-SENSITIVE settings. Nothing here can decrypt the vault.
 * `biometricEnabled` is only a UI flag; the biometric-wrapped key itself is held
 * by Android Keystore + [com.example.offlinepasswordwallet.security.KeyManager].
 */
data class AppSettings(
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val biometricEnabled: Boolean = false,
    val defaultPasswordLength: Int = 20,
    val defaultUseSpecialChars: Boolean = true,
    val clipboardClearSeconds: Int = 30,
    val blockScreenshots: Boolean = true,
)
