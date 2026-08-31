package com.acesoftph.offlinepasswordwallet.tier

import androidx.test.core.app.ApplicationProvider
import com.acesoftph.offlinepasswordwallet.data.model.DefaultFields
import com.acesoftph.offlinepasswordwallet.data.model.ImportMode
import com.acesoftph.offlinepasswordwallet.data.model.VaultDocument
import com.acesoftph.offlinepasswordwallet.data.model.VaultEntry
import com.acesoftph.offlinepasswordwallet.data.model.VaultField
import com.acesoftph.offlinepasswordwallet.data.repository.VaultRepository
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.data.storage.VaultFileStore
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

/**
 * The free tier's entry cap, enforced at the repository rather than the UI.
 *
 * Every one of these goes through [VaultRepository] rather than calling
 * [FreeTier] directly, because the point of the cap is that it holds on all the
 * paths that can add an entry -- not just the "Add entry" button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeTierLimitTest {

    private lateinit var repo: VaultRepository

    private val master = "a-strong-master-1".toCharArray()
    private val answers = listOf("School", "Pet", "Maiden", "Middle", "2001")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "vault").deleteRecursively()
        repo = VaultRepository(VaultFileStore(ctx))
    }

    private fun entry(title: String) = VaultEntry(
        fields = listOf(
            VaultField(DefaultFields.TITLE, title),
            VaultField(DefaultFields.PASSWORD, "pw-$title"),
        ),
    )

    private fun entries(n: Int, prefix: String = "e") = (1..n).map { entry("$prefix$it") }

    private fun count() = (repo.state.value as? VaultState.Unlocked)?.entries?.size ?: -1

    private suspend fun fillToCap() {
        repeat(FreeTier.MAX_ENTRIES) { assertTrue(repo.upsertEntry(entry("e$it")).isSuccess) }
    }

    @Test
    fun `the cap is twenty`() {
        assertEquals(20, FreeTier.MAX_ENTRIES)
    }

    @Test
    fun `entries up to the cap are accepted and the next one is refused`() = runTest {
        repo.createVault(master.copyOf(), answers)
        fillToCap()
        assertEquals(FreeTier.MAX_ENTRIES, count())

        val overflow = repo.upsertEntry(entry("one too many"))
        assertTrue(overflow.isFailure)
        assertTrue(overflow.exceptionOrNull() is FreeTierLimitException)
        assertEquals(FreeTier.MAX_ENTRIES, count())
    }

    @Test
    fun `a full vault can still be edited and deleted from`() = runTest {
        repo.createVault(master.copyOf(), answers)
        fillToCap()

        // Editing must not be collateral damage of the cap: it does not add.
        val existing = (repo.state.value as VaultState.Unlocked).entries.first()
        val renamed = existing.copy(
            fields = listOf(
                VaultField(DefaultFields.TITLE, "renamed"),
                VaultField(DefaultFields.PASSWORD, "still-fine"),
            ),
        )
        assertTrue(repo.upsertEntry(renamed).isSuccess)
        assertEquals(FreeTier.MAX_ENTRIES, count())

        // Deleting frees a slot, and the next add then succeeds.
        assertTrue(repo.deleteEntry(existing.id).isSuccess)
        assertEquals(FreeTier.MAX_ENTRIES - 1, count())
        assertTrue(repo.upsertEntry(entry("now there is room")).isSuccess)
        assertEquals(FreeTier.MAX_ENTRIES, count())
    }

    @Test
    fun `duplicating an entry cannot exceed the cap`() = runTest {
        repo.createVault(master.copyOf(), answers)
        fillToCap()
        val victim = (repo.state.value as VaultState.Unlocked).entries.first()

        val result = repo.duplicateEntry(victim.id)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FreeTierLimitException)
        assertEquals(FreeTier.MAX_ENTRIES, count())
    }

    @Test
    fun `importing in ADD mode fills the remaining room and discards the rest`() = runTest {
        repo.createVault(master.copyOf(), answers)
        repeat(15) { repo.upsertEntry(entry("pre$it")) }

        // 15 present + 12 offered = 27; only the first 5 offered should land.
        assertTrue(repo.importEntries(entries(12, "imp"), ImportMode.ADD).isSuccess)
        assertEquals(FreeTier.MAX_ENTRIES, count())

        val titles = (repo.state.value as VaultState.Unlocked).entries
            .mapNotNull { it.value(DefaultFields.TITLE) }
        assertTrue(titles.contains("imp1"))
        assertTrue(titles.contains("imp5"))
        assertFalse("imp6 is past the cap and must be discarded", titles.contains("imp6"))
    }

    @Test
    fun `importing in REPLACE mode keeps only the first twenty`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(35, "r"), ImportMode.REPLACE).isSuccess)
        assertEquals(FreeTier.MAX_ENTRIES, count())

        val titles = (repo.state.value as VaultState.Unlocked).entries
            .mapNotNull { it.value(DefaultFields.TITLE) }
        assertTrue(titles.contains("r1"))
        assertTrue(titles.contains("r20"))
        assertFalse(titles.contains("r21"))
    }

    @Test
    fun `an import that already fits is untouched`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(7, "small"), ImportMode.REPLACE).isSuccess)
        assertEquals(7, count())
    }

    @Test
    fun `restoring a backup larger than the cap keeps the first twenty`() = runTest {
        // A backup written elsewhere may hold more than this tier allows.
        val oversized = VaultDocument(entries = entries(31, "b"))
        assertTrue(
            repo.replaceVaultWithDocument(master.copyOf(), answers, oversized).isSuccess,
        )
        assertEquals(FreeTier.MAX_ENTRIES, count())

        val titles = (repo.state.value as VaultState.Unlocked).entries
            .mapNotNull { it.value(DefaultFields.TITLE) }
        assertEquals("b1", titles.first())
        assertEquals("b20", titles.last())
        assertFalse(titles.contains("b21"))
    }

    @Test
    fun `the cap survives a lock and unlock round trip`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(40, "p"), ImportMode.REPLACE).isSuccess)
        repo.lock()
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)

        // The truncation was persisted, not just applied to the in-memory copy.
        assertEquals(FreeTier.MAX_ENTRIES, count())
        assertTrue(repo.upsertEntry(entry("still refused")).isFailure)
    }

    @Test
    fun `helpers agree with the cap`() {
        assertFalse(FreeTier.isFull(19))
        assertTrue(FreeTier.isFull(20))
        assertTrue("a corrupt over-cap vault still reads as full", FreeTier.isFull(21))

        assertEquals(1, FreeTier.remaining(19))
        assertEquals(0, FreeTier.remaining(20))
        assertEquals("remaining never goes negative", 0, FreeTier.remaining(25))

        assertEquals(0, FreeTier.discarded(20))
        assertEquals(5, FreeTier.discarded(25))

        assertEquals(3, FreeTier.cap(listOf(1, 2, 3)).size)
        assertEquals(FreeTier.MAX_ENTRIES, FreeTier.cap((1..99).toList()).size)
        assertEquals(listOf(1, 2, 3), FreeTier.cap(listOf(1, 2, 3)))
    }

    @Test
    fun `titles carry the free suffix`() {
        assertEquals("Password List - Free", FreeTier.title("Password List"))
        assertEquals(" - Free", FreeTier.TITLE_SUFFIX)
    }
}
