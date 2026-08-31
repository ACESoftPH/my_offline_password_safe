package com.example.offlinepasswordwallet

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.offlinepasswordwallet.di.ServiceLocator

/**
 * Process entry point. Wires [ServiceLocator] and forwards whole-app
 * foreground/background transitions to the auto-lock manager (§22).
 */
class WalletApplication : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super<Application>.onCreate()
        ServiceLocator.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        ServiceLocator.appLockManager.onEnterForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        ServiceLocator.appLockManager.onEnterBackground()
    }
}
