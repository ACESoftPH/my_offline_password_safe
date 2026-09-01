package com.acesoftph.offlinepasswordwallet.di

import android.content.Context
import com.acesoftph.offlinepasswordwallet.BuildConfig
import com.acesoftph.offlinepasswordwallet.data.backup.BackupManager
import com.acesoftph.offlinepasswordwallet.data.repository.VaultRepository
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.data.repository.EntryCapacityPolicy
import com.acesoftph.offlinepasswordwallet.data.storage.VaultFileStore
import com.acesoftph.offlinepasswordwallet.entitlement.EntitlementManager
import com.acesoftph.offlinepasswordwallet.entitlement.EntitlementOverrideFactory
import com.acesoftph.offlinepasswordwallet.entitlement.KeystoreEntitlementStore
import com.acesoftph.offlinepasswordwallet.entitlement.BillingRepository
import com.acesoftph.offlinepasswordwallet.entitlement.NoBillingRepository
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
    lateinit var entitlementManager: EntitlementManager
        private set
    lateinit var billingRepository: BillingRepository
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext

        settingsRepository = SettingsRepository(app)

        // Entitlement is built before the vault so the vault can be given its
        // capacity policy, but the dependency runs one way only: the entitlement
        // layer never sees the vault, and the vault sees a plain
        // EntryCapacityPolicy rather than anything tier- or billing-shaped
        // (§46B). NoBillingRepository means every install starts FREE and
        // entirely offline until billing is actually wired up (§46D).
        billingRepository = NoBillingRepository()
        entitlementManager = EntitlementManager(
            store = KeystoreEntitlementStore(app),
            billing = billingRepository,
            override = EntitlementOverrideFactory.create(app),
        ).apply { load() }

        vaultRepository = VaultRepository(
            store = VaultFileStore(app),
            capacity = object : EntryCapacityPolicy {
                override fun maxEntries() = entitlementManager.getMaximumEntries()
                override fun capacityMessage() = entitlementManager.capacityMessage()
            },
        )
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
        reconcileEntitlement(applicationScope, entitlementManager)

        initialized = true
    }

    /**
     * Asks the store what the user owns, once, at startup (§46E).
     *
     * One-time products carry no expiry and no receipt to renew, so restoration
     * *is* a re-query: this is the same call the Upgrade screen's "Restore
     * purchases" button makes, and between them they are the whole restoration
     * mechanism. Without it a reinstall would sit on the FREE fallback until the
     * user happened to find that button, because the integrity-protected cache is
     * bound to the install and deliberately does not survive one.
     *
     * Fire-and-forget on the application scope, never on a startup path anything
     * waits for. [EntitlementManager.refreshFromBilling] contains its own
     * failures and treats an unreachable store as "no answer", so the worst case
     * here is that the cached tier stays exactly as it was. Nothing about the
     * vault, unlocking or decryption waits on this coroutine or can be broken by
     * it (§46O, §46P.13).
     *
     * With [NoBillingRepository] wired in this is a no-op that resolves to the
     * cache. It is wired now anyway so that dropping in a real
     * [BillingRepository] is the one-line change §46C promises, rather than a
     * one-line change plus remembering this call.
     *
     * Extracted from [init] for the same reason as [bindAutoLock]: so the
     * contract can be asserted without process-wide singleton state.
     */
    fun reconcileEntitlement(
        scope: CoroutineScope,
        manager: EntitlementManager,
    ): Job = scope.launch { manager.refreshFromBilling() }

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
