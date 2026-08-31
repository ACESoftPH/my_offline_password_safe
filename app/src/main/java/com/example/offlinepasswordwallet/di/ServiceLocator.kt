package com.example.offlinepasswordwallet.di

import android.content.Context
import com.example.offlinepasswordwallet.BuildConfig
import com.example.offlinepasswordwallet.data.backup.BackupManager
import com.example.offlinepasswordwallet.data.repository.VaultRepository
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import com.example.offlinepasswordwallet.security.AppLockManager
import com.example.offlinepasswordwallet.security.KeyManager
import com.example.offlinepasswordwallet.security.MasterPasswordManager
import com.example.offlinepasswordwallet.security.RecoveryManager
import com.example.offlinepasswordwallet.security.RecoveryRateLimiter
import com.example.offlinepasswordwallet.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency wiring. No Hilt/Dagger: for a small security-sensitive app,
 * a hand-written locator keeps the object graph fully auditable in one file.
 *
 * Everything here is process-scoped. Decrypted secrets do NOT live in the
 * locator; they live only inside [VaultRepository] and are cleared on lock.
 */
object ServiceLocator {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var vaultRepository: VaultRepository
        private set
    lateinit var masterPasswordManager: MasterPasswordManager
        private set
    lateinit var recoveryManager: RecoveryManager
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var keyManager: KeyManager
        private set
    lateinit var appLockManager: AppLockManager
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext

        settingsRepository = SettingsRepository(app)
        vaultRepository = VaultRepository(VaultFileStore(app))
        masterPasswordManager = MasterPasswordManager(vaultRepository)
        recoveryManager = RecoveryManager(vaultRepository, RecoveryRateLimiter(app))
        backupManager = BackupManager(vaultRepository, appVersionName = BuildConfig.VERSION_NAME)
        keyManager = KeyManager(app)
        appLockManager = AppLockManager(
            scope = applicationScope,
            settings = settingsRepository.settings,
            onLock = { vaultRepository.lock() },
        )
        initialized = true
    }
}
