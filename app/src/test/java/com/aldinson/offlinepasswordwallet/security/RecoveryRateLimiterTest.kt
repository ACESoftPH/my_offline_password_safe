package com.aldinson.offlinepasswordwallet.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression tests for the review finding that the recovery throttle could be
 * bypassed by deleting/corrupting its state file, by making the file unwritable,
 * or by moving the device clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryRateLimiterTest {

    private lateinit var ctx: android.content.Context
    private lateinit var stateFile: File
    private var wall = 1_000_000L
    private var elapsed = 5_000L

    private fun limiter() = RecoveryRateLimiter(ctx, { wall }, { elapsed })

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val dir = File(ctx.filesDir, "vault")
        dir.deleteRecursively()
        dir.mkdirs()
        stateFile = File(dir, "recovery_state.json")
        wall = 1_000_000L
        elapsed = 5_000L
    }

    private fun failTimes(l: RecoveryRateLimiter, n: Int) = repeat(n) { l.recordFailure() }

    @Test
    fun `first four failures are free, the fifth locks out`() {
        val l = limiter()
        failTimes(l, 4)
        assertFalse(l.isLockedOut())
        l.recordFailure()
        assertEquals(30_000L, l.remainingLockoutMillis())
    }

    @Test
    fun `lockout escalates with further failures`() {
        val l = limiter()
        failTimes(l, 5)
        assertEquals(30_000L, l.remainingLockoutMillis())
        l.recordFailure()
        assertEquals(120_000L, l.remainingLockoutMillis())
        l.recordFailure()
        assertEquals(600_000L, l.remainingLockoutMillis())
    }

    @Test
    fun `lockout persists across a restart of the app`() {
        failTimes(limiter(), 5)
        // Fresh instance = process restart; state comes from disk.
        assertTrue(limiter().isLockedOut())
    }

    @Test
    fun `deleting the state file mid-attack does not reset the running limiter`() {
        val l = limiter()
        failTimes(l, 5)
        assertTrue(stateFile.delete())
        // The in-memory tally is the floor, so an attacker deleting the file
        // between attempts does not get their free attempts back.
        assertTrue(l.isLockedOut())
        l.recordFailure()
        assertEquals(120_000L, l.remainingLockoutMillis()) // continued from 5, not restarted
    }

    @Test
    fun `a corrupt state file is treated as maximum penalty, not a clean slate`() {
        failTimes(limiter(), 5)
        stateFile.writeText("{ this is not valid json")
        val l = limiter()
        assertTrue(l.isLockedOut())
        assertEquals(3_600_000L, l.remainingLockoutMillis())
    }

    @Test
    fun `a truncated state file is treated as maximum penalty`() {
        failTimes(limiter(), 5)
        stateFile.writeText("")
        assertTrue(limiter().isLockedOut())
    }

    @Test
    fun `moving the clock forward does not release an active lockout`() {
        val l = limiter()
        failTimes(l, 5) // 30s lockout, armed at wall=1_000_000 / elapsed=5_000
        assertTrue(l.isLockedOut())

        // Attacker jumps the wall clock far ahead. The monotonic clock has not moved.
        wall += 10_000_000L
        assertTrue("elapsed-time deadline must still hold", l.isLockedOut())

        // Only once real monotonic time has passed does it release.
        elapsed += 31_000L
        assertFalse(l.isLockedOut())
    }

    @Test
    fun `moving the clock backwards re-arms the full penalty rather than locking forever`() {
        val l = limiter()
        failTimes(l, 5)
        wall -= 5_000_000L // clock rewound past the moment the lockout was armed
        assertEquals(30_000L, l.remainingLockoutMillis())
    }

    @Test
    fun `a read-only state file cannot silently disable the throttle`() {
        val l = limiter()
        failTimes(l, 4)
        stateFile.setWritable(false, false)
        try {
            l.recordFailure() // save() fails; in-memory tally must still count it
            assertTrue(l.isLockedOut())
        } finally {
            stateFile.setWritable(true, true)
        }
    }

    @Test
    fun `reset clears both the file and the in-memory tally`() {
        val l = limiter()
        failTimes(l, 6)
        assertTrue(l.isLockedOut())
        l.reset()
        assertFalse(l.isLockedOut())
        assertEquals(0L, l.remainingLockoutMillis())
    }
}
