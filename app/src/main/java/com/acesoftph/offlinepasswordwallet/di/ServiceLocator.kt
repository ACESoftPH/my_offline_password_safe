package com.acesoftph.offlinepasswordwallet.di

import android.content.Context
import com.acesoftph.offlinepasswordwallet.BuildConfig
import com.acesoftph.offlinepasswordwallet.data.backup.BackupManager
import com.acesoftph.offlinepasswordwallet.data.repository.VaultRepository
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.data.storage.VaultFileStore
import com.acesoftph.offlinepasswordwallet.security.AppLockManager
import com.acesoftph.offlinepasswordwallet.security.KeyManager
import com.acesoftph.offlinepasswordwallet.security.MasterPasswordManager
import com.acesoftph.offlinepasswordwallet.security.RecoveryManager
import com.acesoftph.offlinepasswordwallet.security.RecoveryRateLimiter
import com.acesoftph.offlinepasswordwallet.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

        bindAutoLock(applicationScope, vaultRepository, appLockManager)

        initialized = true
    }

    /**
     * Drives the auto-lock timer from the vault's own lock state.
     *
     * Without this bridge [AppLockManager] is never told that the vault was
     * unlocked: its internal `unlocked` flag stays false, `onUserInteraction()`
     * no-ops, the inactivity monitor exits immediately and the return-from-
     * background check is skipped — i.e. the entire Auto-Lock setting is inert and
     * a decrypted vault stays in memory indefinitely.
     *
     * Extracted from [init] so the contract can be asserted in tests without
     * depending on process-wide singleton state.
     */
    fun bindAutoLock(
        scope: CoroutineScope,
        repository: VaultRepository,
        lockManager: AppLockManager,
    ): Job = scope.launch {
        repository.state
            .map { it is VaultState.Unlocked }
            .distinctUntilChanged()
            .collect { unlocked ->
                if (unlocked) lockManager.onUnlocked() else lockManager.onLocked()
            }
    }
}
