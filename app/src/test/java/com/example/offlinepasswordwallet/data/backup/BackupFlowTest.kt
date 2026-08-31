package com.example.offlinepasswordwallet.data.backup

import androidx.test.core.app.ApplicationProvider
import com.example.offlinepasswordwallet.crypto.AeadDecryptionException
import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.ImportMode
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.model.VaultField
import com.example.offlinepasswordwallet.data.repository.VaultRepository
import com.example.offlinepasswordwallet.data.repository.VaultState
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupFlowTest {

    private lateinit var ctx: android.content.Context
    private lateinit var repo: VaultRepository
    private lateinit var manager: BackupManager

    private val master = "device-one-master-1".toCharArray()
    private val answers = listOf("S", "P", "M", "D", "2000")
    private val backupPass = "portable-backup-pass-1".toCharArray()

    @Before
    fun setUp() = runTest {
        ctx = ApplicationProvider.getApplicationContext()
        File(ctx.filesDir, "vault").deleteRecursively()
        repo = VaultRepository(VaultFileStore(ctx))
        manager = BackupManager(repo, appVersionName = "test")
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("Alpha"))
        repo.upsertEntry(entry("Beta"))
    }

    private fun entry(title: String) = VaultEntry(
        fields = listOf(
            VaultField(DefaultFields.TITLE, title),
            VaultField(DefaultFields.PASSWORD, "pw-$title"),
        ),
    )

    private fun entries(r: VaultRepository) = (r.state.value as VaultState.Unlocked).entries

    @Test
    fun `export then restore on a fresh install with a new master password`() = runTest {
        val backupBytes = manager.exportBytes(backupPass.copyOf()).getOrThrow()

        // Simulate a different phone: wipe storage, brand-new objects.
        File(ctx.filesDir, "vault").deleteRecursively()
        val repo2 = VaultRepository(VaultFileStore(ctx))
        val manager2 = BackupManager(repo2, appVersionName = "test")
        assertTrue(!repo2.isInitialized())

        val (preview, document) = manager2.previewAndDecrypt(backupBytes, backupPass.copyOf()).getOrThrow()
        assertEquals(2, preview.entryCount)

        val newMaster = "device-two-master-2".toCharArray()
        val newAnswers = listOf("a", "b", "c", "d", "1990")
        assertTrue(manager2.restoreAsNewVault(document, newMaster.copyOf(), newAnswers).isSuccess)

        repo2.lock()
        assertTrue(repo2.unlockWithMaster(newMaster.copyOf()).isSuccess)
        assertEquals(listOf("Alpha", "Beta"), entries(repo2).map { it.value("Title") })
        assertEquals("pw-Alpha", entries(repo2).first().value("Password"))
        // recovery works with the answers chosen at restore time
        repo2.lock()
        assertTrue(repo2.unlockWithRecoveryAnswers(newAnswers).isSuccess)
    }

    @Test
    fun `wrong backup passphrase is rejected and does not touch storage`() = runTest {
        val backupBytes = manager.exportBytes(backupPass.copyOf()).getOrThrow()
        val result = manager.previewAndDecrypt(backupBytes, "wrong".toCharArray())
        assertTrue(result.isFailure)
        // original vault still fine
        repo.lock()
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)
    }

    @Test
    fun `restore-as-new-vault over an existing vault replaces it`() = runTest {
        val backupBytes = manager.exportBytes(backupPass.copyOf()).getOrThrow()
        // mutate current vault so we can tell it was replaced
        repo.importEntries(listOf(entry("ShouldBeGone")), ImportMode.REPLACE)

        val (_, document) = manager.previewAndDecrypt(backupBytes, backupPass.copyOf()).getOrThrow()
        val newMaster = "replacement-master-3".toCharArray()
        assertTrue(manager.restoreAsNewVault(document, newMaster.copyOf(), answers).isSuccess)

        repo.lock()
        assertTrue(repo.unlockWithMaster(master.copyOf()).exceptionOrNull() is AeadDecryptionException)
        assertTrue(repo.unlockWithMaster(newMaster.copyOf()).isSuccess)
        assertEquals(listOf("Alpha", "Beta"), entries(repo).map { it.value("Title") })
    }

    @Test
    fun `merge into unlocked vault appends the backup entries`() = runTest {
        val backupBytes = manager.exportBytes(backupPass.copyOf()).getOrThrow()
        val (_, document) = manager.previewAndDecrypt(backupBytes, backupPass.copyOf()).getOrThrow()

        assertTrue(manager.mergeIntoUnlockedVault(document, ImportMode.ADD).isSuccess)
        assertEquals(
            listOf("Alpha", "Beta", "Alpha", "Beta"),
            entries(repo).map { it.value("Title") },
        )
    }

    @Test
    fun `export requires an unlocked vault`() {
        repo.lock()
        assertTrue(manager.exportBytes(backupPass.copyOf()).isFailure)
    }
}
