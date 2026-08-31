package com.example.offlinepasswordwallet.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Persists [AppSettings] with Jetpack DataStore.
 *
 * DataStore is used ONLY for non-sensitive preferences. No password, key, salt,
 * answer or vault content is ever written here.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            autoLockTimeout = AutoLockTimeout.fromMillis(
                p[KEY_AUTO_LOCK] ?: AutoLockTimeout.DEFAULT.millis,
            ),
            biometricEnabled = p[KEY_BIOMETRIC] ?: false,
            defaultPasswordLength = p[KEY_GEN_LENGTH] ?: 20,
            defaultUseSpecialChars = p[KEY_GEN_SPECIAL] ?: true,
            clipboardClearSeconds = p[KEY_CLIP_CLEAR] ?: 30,
            blockScreenshots = p[KEY_BLOCK_SCREENSHOTS] ?: true,
        )
    }

    suspend fun setAutoLockTimeout(value: AutoLockTimeout) =
        store.edit { it[KEY_AUTO_LOCK] = value.millis }

    suspend fun setBiometricEnabled(enabled: Boolean) =
        store.edit { it[KEY_BIOMETRIC] = enabled }

    suspend fun setDefaultPasswordLength(length: Int) =
        store.edit { it[KEY_GEN_LENGTH] = length }

    suspend fun setDefaultUseSpecialChars(use: Boolean) =
        store.edit { it[KEY_GEN_SPECIAL] = use }

    suspend fun setClipboardClearSeconds(seconds: Int) =
        store.edit { it[KEY_CLIP_CLEAR] = seconds }

    suspend fun setBlockScreenshots(block: Boolean) =
        store.edit { it[KEY_BLOCK_SCREENSHOTS] = block }

    private companion object {
        val KEY_AUTO_LOCK = longPreferencesKey("auto_lock_millis")
        val KEY_BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val KEY_GEN_LENGTH = intPreferencesKey("gen_default_length")
        val KEY_GEN_SPECIAL = booleanPreferencesKey("gen_default_special")
        val KEY_CLIP_CLEAR = intPreferencesKey("clipboard_clear_seconds")
        val KEY_BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
    }
}
