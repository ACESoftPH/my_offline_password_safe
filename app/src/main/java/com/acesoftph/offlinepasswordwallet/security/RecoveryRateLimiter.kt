package com.acesoftph.offlinepasswordwallet.security

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Persistent rate limiting / temporary lockout for the "Reset Master Password"
 * recovery flow (§3.4).
 *
 * The five security answers are the lowest-entropy way into the vault, so this
 * throttle is the thing standing between a motivated guesser and the DEK. It is
 * therefore built to **fail closed**:
 *
 *  - **Corrupt/unreadable state is treated as maximum penalty**, not as a clean
 *    slate. Deleting or truncating `recovery_state.json` to wipe the counter now
 *    makes things worse for the attacker, not better. (A genuinely *absent* file
 *    is still the legitimate first-run case and is allowed.)
 *  - **Failed writes do not lose the count.** An in-memory tally is kept
 *    alongside the file and the effective attempt count is the max of the two, so
 *    making the file read-only cannot silently disable the throttle for the life
 *    of the process.
 *  - **Two clocks.** The deadline is stored against the wall clock (survives a
 *    reboot) *and* enforced against [SystemClock.elapsedRealtime] (immune to
 *    clock changes). The remaining lockout is the larger of the two, and a wall
 *    clock that has moved *backwards* since the lockout was armed is treated as
 *    tampering and re-arms the full penalty.
 *
 * Schedule — after the free attempts are used up, each further failure re-arms a
 * longer lockout:
 *   attempts 1-4 : allowed immediately
 *   attempt   5  : 30s      attempt 6 : 2m
 *   attempt   7  : 10m      attempt 8 : 30m
 *   attempt  9+  : 1h
 *
 * The file holds counters and timestamps only — never any secret material.
 */
class RecoveryRateLimiter(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val dir = File(context.applicationContext.filesDir, "vault")
    private val file = File(dir, "recovery_state.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    data class State(
        val failedAttempts: Int = 0,
        /** Wall-clock instant the current lockout expires (0 = none). */
        val lockedUntilWallMillis: Long = 0L,
        /** Wall-clock instant the current lockout was armed, to detect clock rewind. */
        val lockedAtWallMillis: Long = 0L,
    )

    // Process-lifetime floor, so a failed write cannot erase the tally.
    @Volatile private var memoryAttempts: Int = 0
    @Volatile private var memoryLockedUntilElapsed: Long = 0L

    /** Milliseconds remaining before another attempt is allowed (0 = allowed now). */
    fun remainingLockoutMillis(): Long {
        val state = load()
        val now = wallClock()

        val wallRemaining = when {
            state.lockedUntilWallMillis <= 0L -> 0L
            // Clock moved backwards past the moment the lockout was armed: treat
            // the whole window as still outstanding rather than trusting `now`.
            now < state.lockedAtWallMillis ->
                state.lockedUntilWallMillis - state.lockedAtWallMillis
            else -> state.lockedUntilWallMillis - now
        }.coerceAtLeast(0L)

        val elapsedRemaining = (memoryLockedUntilElapsed - elapsedClock()).coerceAtLeast(0L)
        return max(wallRemaining, elapsedRemaining)
    }

    fun isLockedOut(): Boolean = remainingLockoutMillis() > 0

    fun recordFailure() {
        val attempts = max(load().failedAttempts, memoryAttempts) + 1
        memoryAttempts = attempts
        val penalty = penaltyFor(attempts)
        if (penalty > 0) memoryLockedUntilElapsed = elapsedClock() + penalty
        val now = wallClock()
        save(
            State(
                failedAttempts = attempts,
                lockedUntilWallMillis = if (penalty > 0) now + penalty else 0L,
                lockedAtWallMillis = if (penalty > 0) now else 0L,
            ),
        )
    }

    fun reset() {
        memoryAttempts = 0
        memoryLockedUntilElapsed = 0L
        save(State())
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private fun penaltyFor(attempts: Int): Long = when {
        attempts <= FREE_ATTEMPTS -> 0L
        attempts == 5 -> 30_000L
        attempts == 6 -> 120_000L
        attempts == 7 -> 600_000L
        attempts == 8 -> 1_800_000L
        else -> 3_600_000L
    }

    /**
     * A missing file is the legitimate first-run state. A file that exists but
     * cannot be read or parsed is treated as tampering and yields the maximum
     * penalty, so wiping the throttle is never an attacker's win.
     */
    private fun load(): State {
        if (!file.isFile) {
            return State(failedAttempts = memoryAttempts)
        }
        return runCatching { json.decodeFromString(State.serializer(), file.readText()) }
            .getOrElse {
                val now = wallClock()
                State(
                    failedAttempts = max(MAX_PENALTY_ATTEMPTS, memoryAttempts),
                    lockedUntilWallMillis = now + penaltyFor(MAX_PENALTY_ATTEMPTS),
                    lockedAtWallMillis = now,
                )
            }
    }

    private fun save(state: State) {
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "recovery_state.json.tmp")
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(State.serializer(), state).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
        // A failed write is deliberately not fatal: the in-memory tally above
        // still enforces the throttle for this process.
    }

    private companion object {
        const val FREE_ATTEMPTS = 4
        /** Attempt number whose penalty is used when the state file looks tampered. */
        const val MAX_PENALTY_ATTEMPTS = 9
    }
}
