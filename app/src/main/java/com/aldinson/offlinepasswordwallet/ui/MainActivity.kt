package com.aldinson.offlinepasswordwallet.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aldinson.offlinepasswordwallet.di.ServiceLocator
import com.aldinson.offlinepasswordwallet.ui.theme.OfflinePasswordWalletTheme
import kotlinx.coroutines.launch

/**
 * The single Activity. Extends [FragmentActivity] because AndroidX
 * `BiometricPrompt` requires it.
 *
 * Security-relevant behaviour here:
 *  - Applies `FLAG_SECURE` (blocks screenshots / screen recording / hides the app
 *    from the recents thumbnail) whenever the "Block screenshots" setting is on
 *    (§23). Default on.
 *  - Reports every user interaction to [com.aldinson.offlinepasswordwallet.security.AppLockManager]
 *    so the inactivity timer is accurate (§21).
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Applied BEFORE anything can be drawn. Reading the preference is async
        // (DataStore), so we start secure and only relax it if the user has
        // actually turned the setting off — otherwise the first frames and the
        // recents thumbnail would be capturable on every cold start.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.settingsRepository.settings.collect { settings ->
                    if (settings.blockScreenshots) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        setContent {
            OfflinePasswordWalletTheme {
                WalletRoot(activity = this)
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ServiceLocator.appLockManager.onUserInteraction()
    }
}
