package com.example.offlinepasswordwallet.di

import androidx.test.core.app.ApplicationProvider
import com.example.offlinepasswordwallet.data.repository.VaultRepository
import com.example.offlinepasswordwallet.data.repository.VaultState
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import com.example.offlinepasswordwallet.security.AppLockManager
import com.example.offlinepasswordwallet.settings.AppSettings
import com.example.offlinepasswordwallet.settings.AutoLockTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Regression test for the review's top finding: [AppLockManager] was constructed
 * but `onUnlocked()` / `onLocked()` were never called from anywhere in production
 * code, so its `unlocked` flag stayed false forever and inactivity / background
 * auto-lock silently never fired.
 *
 * This exercises the real production wiring function, [ServiceLocator.bindAutoLock],
 * against a locally built repository so it does not depend on process-wide
 * singleton state (Robolectric boots `WalletApplication`, and therefore
 * `ServiceLocator.init`, once per sandbox).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoLockWiringTest {

    private lateinit var repo: VaultRepository
    private val master = "wiring-test-master-1".toCharArray()
    private val answers = listOf("a", "b", "c", "d", "2000")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.filesDir, "autolock-wiring-test")
        dir.deleteRecursively()
        repo = VaultRepository(VaultFileStore(TestFilesDirContext(ctx, dir)))
    }

    @Test
    fun `unlocking arms the auto-lock timer and locking disarms it`() = runTest {
        val clock = longArrayOf(0L)
        val lockManager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.SEC_30)),
            onLock = { repo.lock() },
            now = { clock[0] },
        )
        ServiceLocator.bindAutoLock(backgroundScope, repo, lockManager)
        runCurrent()
        assertFalse("starts disarmed", lockManager.isArmed)

        val created = repo.createVault(master.copyOf(), answers)
        assertTrue("vault setup failed: ${created.exceptionOrNull()}", created.isSuccess)
        assertTrue(repo.state.value is VaultState.Unlocked)

        runCurrent()
        assertTrue("bindAutoLock must arm the timer on unlock", lockManager.isArmed)

        repo.lock()
        runCurrent()
        assertFalse("bindAutoLock must disarm the timer on lock", lockManager.isArmed)
    }

    @Test
    fun `the wired timer actually locks the vault after the timeout`() = runTest {
        val clock = longArrayOf(0L)
        val lockManager = AppLockManager(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(autoLockTimeout = AutoLockTimeout.SEC_30)),
            onLock = { repo.lock() },
            now = { clock[0] },
        )
        ServiceLocator.bindAutoLock(backgroundScope, repo, lockManager)
        runCurrent()

        assertTrue(repo.createVault(master.copyOf(), answers).isSuccess)
        runCurrent()
        assertTrue(repo.state.value is VaultState.Unlocked)

        clock[0] = 31_000
        advanceTimeBy(32_000)
        runCurrent()

        assertTrue(
            "an idle vault must end up Locked, not left decrypted in memory",
            repo.state.value is VaultState.Locked,
        )
    }
}

/** Redirects [android.content.Context.getFilesDir] so each test gets its own vault dir. */
private class TestFilesDirContext(
    base: android.content.Context,
    private val filesDirOverride: File,
) : android.content.ContextWrapper(base) {
    override fun getFilesDir(): File = filesDirOverride.apply { mkdirs() }
    override fun getApplicationContext(): android.content.Context = this
}
