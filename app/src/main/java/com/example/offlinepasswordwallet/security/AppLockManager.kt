package com.example.offlinepasswordwallet.security

import android.os.SystemClock
import com.example.offlinepasswordwallet.settings.AppSettings
import com.example.offlinepasswordwallet.settings.AutoLockTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Inactivity auto-lock (§21–§22).
 *
 * "Locking" here means: drop every in-memory reference to decrypted vault data
 * (via [onLock], wired to `VaultRepository.lock()`), so the UI falls back to the
 * unlock screen and nothing sensitive is reachable until the user re-authenticates.
 *
 * This deliberately does NOT claim to kill the process — Android does not
 * guarantee an app can terminate itself (§21). It clears references and closes
 * sensitive state; surviving JVM string copies are a documented limitation.
 *
 * Time source is [SystemClock.elapsedRealtime] (monotonic, counts time while the
 * device sleeps) so a device left in a pocket still locks on the next resume.
 */
class AppLockManager(
    private val scope: CoroutineScope,
    settings: Flow<AppSettings>,
    private val onLock: () -> Unit,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    @Volatile private var timeoutMillis: Long = AutoLockTimeout.DEFAULT.millis
    @Volatile private var lastInteraction: Long = now()
    @Volatile private var backgroundedAt: Long? = null
    @Volatile private var unlocked: Boolean = false
    private var monitorJob: Job? = null

    init {
        scope.launch {
            settings.collect {
                timeoutMillis = it.autoLockTimeout.millis
                if (unlocked) restartMonitor()
            }
        }
    }

    fun onUnlocked() {
        unlocked = true
        lastInteraction = now()
        backgroundedAt = null
        restartMonitor()
    }

    fun onLocked() {
        unlocked = false
        monitorJob?.cancel()
        monitorJob = null
    }

    /** Called from the Activity for every meaningful touch / nav / edit event. */
    fun onUserInteraction() {
        if (unlocked) lastInteraction = now()
    }

    fun onEnterForeground() {
        val bg = backgroundedAt
        backgroundedAt = null
        if (unlocked && bg != null && timeoutMillis != AutoLockTimeout.NEVER.millis &&
            now() - bg >= timeoutMillis
        ) {
            triggerLock()
            return
        }
        if (unlocked) restartMonitor()
    }

    fun onEnterBackground() {
        backgroundedAt = now()
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun triggerLock() {
        if (!unlocked) return
        unlocked = false
        monitorJob?.cancel()
        monitorJob = null
        onLock()
    }

    private fun restartMonitor() {
        monitorJob?.cancel()
        if (timeoutMillis == AutoLockTimeout.NEVER.millis) {
            monitorJob = null
            return
        }
        monitorJob = scope.launch {
            while (isActive && unlocked) {
                val idle = now() - lastInteraction
                if (idle >= timeoutMillis) {
                    triggerLock()
                    break
                }
                delay(POLL_MILLIS)
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
    }
}
