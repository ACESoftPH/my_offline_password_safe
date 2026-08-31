package com.example.offlinepasswordwallet.security

import androidx.test.core.app.ApplicationProvider
import com.example.offlinepasswordwallet.data.repository.VaultRepository
import com.example.offlinepasswordwallet.data.repository.VaultState
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryFlowTest {

    private lateinit var ctx: android.content.Context
    private lateinit var repo: VaultRepository
    private var fakeNow = 1_000_000L
    private lateinit var limiter: RecoveryRateLimiter
    private lateinit var manager: RecoveryManager

    private val master = "original-master-phrase-1".toCharArray()
    private val answers = listOf("Oakwood", "Bandit", "Nguyen", "Alexander", "2008")

    @Before
    fun setUp() = runTest {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "vault").deleteRecursively()
        repo = VaultRepository(VaultFileStore(ctx))
        limiter = RecoveryRateLimiter(ctx) { fakeNow }
        manager = RecoveryManager(repo, limiter)
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(
            com.example.offlinepasswordwallet.data.model.VaultEntry(
                fields = listOf(
                    com.example.offlinepasswordwallet.data.model.VaultField("Title", "MyEntry"),
                ),
            ),
        )
        repo.lock()
    }

    @Test
    fun `correct five answers permit reset`() = runTest {
        assertEquals(RecoveryResult.Verified, manager.submitAnswers(answers))
        val newMaster = "chosen-after-recovery-2".toCharArray()
        assertTrue(manager.applyNewMasterPassword(newMaster.copyOf()).isSuccess)
        repo.lock()
        assertTrue(repo.unlockWithMaster(newMaster.copyOf()).isSuccess)
        assertEquals(
            "MyEntry",
            (repo.state.value as VaultState.Unlocked).entries.single().value("Title"),
        )
    }

    @Test
    fun `incorrect answers do not verify and do not touch the vault`() = runTest {
        val r = manager.submitAnswers(listOf("x", "x", "x", "x", "x"))
        assertTrue(r is RecoveryResult.Incorrect)
        repo.lock()
        // original master still works, vault intact
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)
    }

    @Test
    fun `partial answers do not verify`() = runTest {
        val r = manager.submitAnswers(listOf("Oakwood", "Bandit", "Nguyen", "Alexander", "WRONG"))
        assertTrue(r is RecoveryResult.Incorrect)
    }

    @Test
    fun `rate limiting kicks in after repeated failures and persists`() = runTest {
        repeat(4) {
            assertTrue(manager.submitAnswers(listOf("no", "no", "no", "no", "no")) is RecoveryResult.Incorrect)
        }
        // 5th failure -> lockout
        val fifth = manager.submitAnswers(listOf("no", "no", "no", "no", "no"))
        assertTrue(fifth is RecoveryResult.Incorrect && fifth.lockedOutMillis > 0)

        // even correct answers are refused while locked out
        assertTrue(manager.submitAnswers(answers) is RecoveryResult.LockedOut)

        // a fresh limiter instance (app restart) still sees the lockout
        val restarted = RecoveryManager(repo, RecoveryRateLimiter(ctx) { fakeNow })
        assertTrue(restarted.submitAnswers(answers) is RecoveryResult.LockedOut)

        // after the lockout window passes, correct answers work again
        fakeNow += 60_000
        assertEquals(RecoveryResult.Verified, restarted.submitAnswers(answers))
    }

    @Test
    fun `successful verification clears the failure counter`() = runTest {
        repeat(3) { manager.submitAnswers(listOf("no", "no", "no", "no", "no")) }
        assertEquals(RecoveryResult.Verified, manager.submitAnswers(answers))
        assertFalse(limiter.isLockedOut())
        assertEquals(0L, limiter.remainingLockoutMillis())
    }

    @Test
    fun `recovery never exposes the old master password`() {
        // There is simply no API that returns it. Assert the surface stays that way.
        val methods = RecoveryManager::class.java.methods.map { it.name }
        assertFalse(methods.any { it.contains("old", ignoreCase = true) })
        assertFalse(methods.any { it.contains("reveal", ignoreCase = true) })
        assertFalse(methods.any { it.contains("getMaster", ignoreCase = true) })
    }
}
