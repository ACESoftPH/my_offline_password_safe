package com.acesoft.offlinepasswordwallet.security

import com.acesoft.offlinepasswordwallet.settings.AppSettings
import com.acesoft.offlinepasswordwallet.settings.AutoLockTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockManagerTest {

    private class Clock(var nowMs: Long = 0L)

    @Test
    fun `locks after the configured inactivity timeout`() = runTest {
        val clock = Clock()
        var locked = false
        val settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.SEC_30))
        val manager = AppLockManager(
            scope = backgroundScope,
            settings = settings,
            onLock = { locked = true },
            now = { clock.nowMs },
        )
        manager.onUnlocked()
        runCurrent()

        clock.nowMs = 29_000
        advanceTimeBy(30_000); runCurrent()
        assertFalse("not yet timed out", locked)

        clock.nowMs = 31_000
        advanceTimeBy(2_000); runCurrent()
        assertTrue("should have locked", locked)
    }

    @Test
    fun `user interaction resets the timer`() = runTest {
        val clock = Clock()
        var locked = false
        val manager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.SEC_30)),
            onLock = { locked = true },
            now = { clock.nowMs },
        )
        manager.onUnlocked()
        runCurrent()

        clock.nowMs = 25_000
        advanceTimeBy(25_000); runCurrent()
        manager.onUserInteraction() // resets lastInteraction to now (25s)
        assertFalse(locked)

        clock.nowMs = 50_000
        advanceTimeBy(25_000); runCurrent()
        assertFalse("still within 30s of last interaction", locked)

        clock.nowMs = 56_000
        advanceTimeBy(6_000); runCurrent()
        assertTrue(locked)
    }

    @Test
    fun `returning from background after timeout locks immediately`() = runTest {
        val clock = Clock()
        var locked = false
        val manager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.MIN_1)),
            onLock = { locked = true },
            now = { clock.nowMs },
        )
        manager.onUnlocked(); runCurrent()

        clock.nowMs = 10_000
        manager.onEnterBackground()
        clock.nowMs = 10_000 + 61_000
        manager.onEnterForeground()
        assertTrue(locked)
    }

    @Test
    fun `returning from a short background does not lock`() = runTest {
        val clock = Clock()
        var locked = false
        val manager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.MIN_5)),
            onLock = { locked = true },
            now = { clock.nowMs },
        )
        manager.onUnlocked(); runCurrent()

        clock.nowMs = 10_000
        manager.onEnterBackground()
        clock.nowMs = 20_000
        manager.onEnterForeground()
        runCurrent()
        assertFalse(locked)
    }

    @Test
    fun `NEVER timeout never auto-locks`() = runTest {
        val clock = Clock()
        var locked = false
        val manager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.NEVER)),
            onLock = { locked = true },
            now = { clock.nowMs },
        )
        manager.onUnlocked(); runCurrent()

        clock.nowMs = 10_000_000
        advanceTimeBy(10_000_000); runCurrent()
        assertFalse(locked)
    }

    @Test
    fun `timeout enum default is five minutes`() {
        assertEquals(AutoLockTimeout.MIN_5, AutoLockTimeout.DEFAULT)
        assertEquals(300_000L, AutoLockTimeout.MIN_5.millis)
    }
}
