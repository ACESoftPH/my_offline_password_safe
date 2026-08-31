package com.example.offlinepasswordwallet.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent rate limiting / temporary lockout for the "Reset Master Password"
 * recovery flow (§3.4).
 *
 * State (failed-attempt count + lockout deadline) is stored on disk so that
 * killing and relaunching the app cannot reset the throttle. It holds NO secret
 * material — only counters and a timestamp.
 *
 * Schedule (after the free attempts are used up, each further failure re-arms a
 * longer lockout):
 *   attempts 1-4 : allowed immediately
 *   attempt   5  : 30s lockout
 *   attempt   6  : 2m
 *   attempt   7  : 10m
 *   attempt   8  : 30m
 *   attempt  9+  : 1h
 */
class RecoveryRateLimiter(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(File(context.applicationContext.filesDir, "vault"), "recovery_state.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    data class State(val failedAttempts: Int = 0, val lockedUntil: Long = 0L)

    private fun load(): State =
        runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())

    private fun save(state: State) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(State.serializer(), state))
        }
    }

    /** Milliseconds remaining before another attempt is allowed (0 = allowed now). */
    fun remainingLockoutMillis(): Long {
        val s = load()
        return (s.lockedUntil - now()).coerceAtLeast(0L)
    }

    fun isLockedOut(): Boolean = remainingLockoutMillis() > 0

    fun recordFailure() {
        val s = load()
        val attempts = s.failedAttempts + 1
        val penalty = when {
            attempts < FREE_ATTEMPTS + 1 -> 0L
            attempts == 5 -> 30_000L
            attempts == 6 -> 120_000L
            attempts == 7 -> 600_000L
            attempts == 8 -> 1_800_000L
            else -> 3_600_000L
        }
        save(State(failedAttempts = attempts, lockedUntil = if (penalty > 0) now() + penalty else 0L))
    }

    fun reset() = save(State())

    private companion object {
        const val FREE_ATTEMPTS = 4
    }
}
