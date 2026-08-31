package com.example.offlinepasswordwallet.data.repository

import androidx.test.core.app.ApplicationProvider
import com.example.offlinepasswordwallet.crypto.AeadDecryptionException
import com.example.offlinepasswordwallet.data.model.DefaultFields
import com.example.offlinepasswordwallet.data.model.ImportMode
import com.example.offlinepasswordwallet.data.model.VaultEntry
import com.example.offlinepasswordwallet.data.model.VaultField
import com.example.offlinepasswordwallet.data.storage.VaultFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultRepositoryTest {

    private lateinit var store: VaultFileStore
    private lateinit var repo: VaultRepository

    private val master = "a-strong-master-1".toCharArray()
    private val answers = listOf("School", "Pet", "Maiden", "Middle", "2001")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "vault").deleteRecursively()
        store = VaultFileStore(ctx)
        repo = VaultRepository(store)
    }

    private fun entry(title: String, password: String = "pw") = VaultEntry(
        fields = listOf(
            VaultField(DefaultFields.TITLE, title),
            VaultField(DefaultFields.PASSWORD, password),
        ),
    )

    @Test
    fun `create initializes and unlocks`() = runTest {
        assertFalse(repo.isInitialized())
        assertTrue(repo.createVault(master.copyOf(), answers).isSuccess)
        assertTrue(repo.isInitialized())
        assertTrue(repo.state.value is VaultState.Unlocked)
    }

    @Test
    fun `lock clears in-memory state and requires auth`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("Secret"))
        repo.lock()

        assertEquals(VaultState.Locked, repo.state.value)
        assertNull(repo.currentDocument())
        assertNull(repo.currentDek())
        // mutations are rejected while locked
        assertTrue(repo.upsertEntry(entry("nope")).isFailure)
    }

    @Test
    fun `unlock with correct and incorrect master password`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.lock()

        assertTrue(repo.unlockWithMaster("totally-wrong".toCharArray()).exceptionOrNull() is AeadDecryptionException)
        assertEquals(VaultState.Locked, repo.state.value)

        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)
        assertTrue(repo.state.value is VaultState.Unlocked)
    }

    @Test
    fun `entry add edit delete duplicate`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val e = entry("Gmail")
        repo.upsertEntry(e)
        assertEquals(1, entries().size)

        repo.upsertEntry(e.copy(fields = listOf(VaultField(DefaultFields.TITLE, "Gmail 2"))))
        assertEquals("Gmail 2", entries().single().value("Title"))

        repo.duplicateEntry(e.id)
        assertEquals(2, entries().size)
        assertTrue(entries().any { it.value("Title") == "Gmail 2 (copy)" })

        repo.deleteEntry(e.id)
        assertEquals(1, entries().size)
    }

    @Test
    fun `import add appends, import replace overwrites`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("Existing"))

        repo.importEntries(listOf(entry("Imported A"), entry("Imported B")), ImportMode.ADD)
        assertEquals(3, entries().size)

        repo.importEntries(listOf(entry("Only One")), ImportMode.REPLACE)
        assertEquals(listOf("Only One"), entries().map { it.value("Title") })
    }

    @Test
    fun `changes persist across a fresh repository instance`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("Persisted"))
        repo.lock()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo2 = VaultRepository(VaultFileStore(ctx))
        assertTrue(repo2.isInitialized())
        assertTrue(repo2.unlockWithMaster(master.copyOf()).isSuccess)
        assertEquals("Persisted", (repo2.state.value as VaultState.Unlocked).entries.single().value("Title"))
    }

    @Test
    fun `change master password - old fails, new works, data intact`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("KeepMe"))

        val newMaster = "an-even-stronger-master-2".toCharArray()
        assertTrue(repo.changeMasterPassword(master.copyOf(), newMaster.copyOf()).isSuccess)
        repo.lock()

        assertTrue(repo.unlockWithMaster(master.copyOf()).exceptionOrNull() is AeadDecryptionException)
        assertTrue(repo.unlockWithMaster(newMaster.copyOf()).isSuccess)
        assertEquals("KeepMe", entries().single().value("Title"))
    }

    @Test
    fun `wrong current password does not change master`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val res = repo.changeMasterPassword("wrong".toCharArray(), "new-master-value-1".toCharArray())
        assertTrue(res.isFailure)
        repo.lock()
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess) // original still valid
    }

    @Test
    fun `recovery reset - new master set, old never revealed, entries kept`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repo.upsertEntry(entry("Survivor"))
        repo.lock()

        assertTrue(repo.unlockWithRecoveryAnswers(answers).isSuccess)
        val newMaster = "post-recovery-master-3".toCharArray()
        assertTrue(repo.setMasterPasswordAfterRecovery(newMaster.copyOf()).isSuccess)
        repo.lock()

        assertTrue(repo.unlockWithMaster(master.copyOf()).exceptionOrNull() is AeadDecryptionException)
        assertTrue(repo.unlockWithMaster(newMaster.copyOf()).isSuccess)
        assertEquals("Survivor", entries().single().value("Title"))
    }

    @Test
    fun `change security answers - old answers fail, new answers work`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val newAnswers = listOf("A", "B", "C", "D", "1999")
        assertTrue(repo.changeSecurityAnswers(master.copyOf(), newAnswers).isSuccess)
        repo.lock()

        assertTrue(repo.unlockWithRecoveryAnswers(answers).exceptionOrNull() is AeadDecryptionException)
        assertTrue(repo.unlockWithRecoveryAnswers(newAnswers).isSuccess)
    }

    // --- regression: importing entries that already exist must not clone ids ---

    @Test
    fun `import ADD re-ids entries whose ids already exist`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val existing = entry("Mine")
        repo.upsertEntry(existing)

        // Same objects, same ids — exactly what restoring a backup of this vault does.
        repo.importEntries(listOf(existing), ImportMode.ADD)

        val all = entries()
        assertEquals(2, all.size)
        assertEquals(2, all.map { it.id }.toSet().size)
    }

    @Test
    fun `re-ided duplicates can be deleted and edited independently`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val original = entry("Mine")
        repo.upsertEntry(original)
        repo.importEntries(listOf(original), ImportMode.ADD)

        val clone = entries().first { it.id != original.id }
        repo.deleteEntry(clone.id)
        assertEquals(listOf(original.id), entries().map { it.id })
    }

    @Test
    fun `import ADD de-duplicates ids within the imported batch itself`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val e = entry("Twin")
        repo.importEntries(listOf(e, e, e), ImportMode.ADD)
        assertEquals(3, entries().size)
        assertEquals(3, entries().map { it.id }.toSet().size)
    }

    private fun entries(): List<VaultEntry> =
        (repo.state.value as VaultState.Unlocked).entries
}
