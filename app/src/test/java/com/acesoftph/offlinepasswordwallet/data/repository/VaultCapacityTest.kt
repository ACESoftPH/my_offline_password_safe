package com.acesoftph.offlinepasswordwallet.data.repository

import androidx.test.core.app.ApplicationProvider
import com.acesoftph.offlinepasswordwallet.data.model.DefaultFields
import com.acesoftph.offlinepasswordwallet.data.model.ImportMode
import com.acesoftph.offlinepasswordwallet.data.model.VaultDocument
import com.acesoftph.offlinepasswordwallet.data.model.VaultEntry
import com.acesoftph.offlinepasswordwallet.data.model.VaultField
import com.acesoftph.offlinepasswordwallet.data.storage.VaultFileStore
import com.acesoftph.offlinepasswordwallet.entitlement.ProductCatalog
import com.acesoftph.offlinepasswordwallet.entitlement.SubscriptionTier
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
 * Capacity enforcement in the vault itself (§46G, §46H, §46P.6-11).
 *
 * The capacity is driven by a mutable policy so a tier change -- an upgrade, a
 * downgrade, a refund -- can be simulated mid-test without any billing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCapacityTest {

    /** Stands in for the entitlement layer, switchable at will. */
    private class TierPolicy(var tier: SubscriptionTier) : EntryCapacityPolicy {
        override fun maxEntries() = ProductCatalog.maxEntriesFor(tier)
        override fun capacityMessage() = "Your ${tier.displayName} vault is full."
    }

    private lateinit var policy: TierPolicy
    private lateinit var repo: VaultRepository

    private val master = "a-strong-master-1".toCharArray()
    private val answers = listOf("School", "Pet", "Maiden", "Middle", "2001")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "vault").deleteRecursively()
        policy = TierPolicy(SubscriptionTier.FREE)
        repo = VaultRepository(store = VaultFileStore(ctx), capacity = policy)
    }

    private fun entry(title: String) = VaultEntry(
        fields = listOf(
            VaultField(DefaultFields.TITLE, title),
            VaultField(DefaultFields.PASSWORD, "pw-$title"),
        ),
    )

    private fun entries(n: Int, prefix: String = "e") = (1..n).map { entry("$prefix$it") }
    private fun count() = (repo.state.value as? VaultState.Unlocked)?.entries?.size ?: -1
    private fun titles() = (repo.state.value as VaultState.Unlocked).entries
        .mapNotNull { it.value(DefaultFields.TITLE) }

    // -- §46P.6-10: the cap at each tier -------------------------------------

    @Test
    fun `each tier admits exactly its capacity and refuses the next entry`() = runTest {
        repo.createVault(master.copyOf(), answers)
        val cases = listOf(
            SubscriptionTier.FREE to 20,
            SubscriptionTier.PLUS to 100,
            SubscriptionTier.PRO to 500,
            SubscriptionTier.ULTIMATE to 1_000,
        )
        for ((tier, max) in cases) {
            policy.tier = tier
            // Fill by import (one write) rather than `max` separate saves.
            assertTrue(repo.importEntries(entries(max + 25, "t"), ImportMode.REPLACE).isSuccess)
            assertEquals("$tier must hold exactly $max", max, count())

            val refused = repo.upsertEntry(entry("one too many"))
            assertTrue("$tier must refuse entry #${max + 1}", refused.isFailure)
            assertTrue(refused.exceptionOrNull() is EntryCapacityReachedException)
            assertEquals(max, count())
        }
    }

    @Test
    fun `unlimited applies no cap`() = runTest {
        repo.createVault(master.copyOf(), answers)
        policy.tier = SubscriptionTier.UNLIMITED
        assertTrue(repo.importEntries(entries(1_500, "u"), ImportMode.REPLACE).isSuccess)
        assertEquals(1_500, count())
        assertTrue(repo.upsertEntry(entry("and another")).isSuccess)
        assertEquals(1_501, count())
    }

    // -- §46P.11 / §46H: a downgrade must never cost data --------------------

    @Test
    fun `downgrading keeps every entry readable, editable and deletable`() = runTest {
        repo.createVault(master.copyOf(), answers)
        policy.tier = SubscriptionTier.PRO
        assertTrue(repo.importEntries(entries(150, "keep"), ImportMode.REPLACE).isSuccess)
        assertEquals(150, count())

        // Entitlement is lost. 150 entries against a 20-entry cap.
        policy.tier = SubscriptionTier.FREE

        // 1. Nothing is deleted or hidden.
        assertEquals("no entry may be dropped by a downgrade", 150, count())
        assertTrue(titles().contains("keep1"))
        assertTrue(titles().contains("keep150"))

        // 2. Existing entries remain editable, even far over capacity.
        val victim = (repo.state.value as VaultState.Unlocked).entries[99]
        val edited = victim.copy(
            fields = listOf(
                VaultField(DefaultFields.TITLE, "edited while over capacity"),
                VaultField(DefaultFields.PASSWORD, "new-pw"),
            ),
        )
        assertTrue(repo.upsertEntry(edited).isSuccess)
        assertEquals(150, count())
        assertTrue(titles().contains("edited while over capacity"))

        // 3. Creating is refused.
        assertTrue(repo.upsertEntry(entry("nope")).isFailure)

        // 4. Deleting works and is the way back under the cap.
        assertTrue(repo.deleteEntry(victim.id).isSuccess)
        assertEquals(149, count())

        // 5. It survives a lock/unlock: the file was never truncated.
        repo.lock()
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)
        assertEquals(149, count())
    }

    @Test
    fun `upgrading immediately grants the new capacity without touching the vault`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(20, "f"), ImportMode.REPLACE).isSuccess)
        assertTrue(repo.upsertEntry(entry("blocked at free")).isFailure)

        policy.tier = SubscriptionTier.PLUS

        // §46I: no re-creation, no migration, the same entries.
        assertEquals(20, count())
        assertTrue(titles().contains("f1"))
        assertTrue(repo.upsertEntry(entry("allowed at plus")).isSuccess)
        assertEquals(21, count())
    }

    // -- §46G: every path that adds an entry is covered ----------------------

    @Test
    fun `duplicating is refused at the cap`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(20, "d"), ImportMode.REPLACE).isSuccess)
        val victim = (repo.state.value as VaultState.Unlocked).entries.first()

        val result = repo.duplicateEntry(victim.id)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EntryCapacityReachedException)
        assertEquals(20, count())
    }

    @Test
    fun `import in ADD mode fills only the remaining room`() = runTest {
        repo.createVault(master.copyOf(), answers)
        assertTrue(repo.importEntries(entries(15, "pre"), ImportMode.ADD).isSuccess)
        assertTrue(repo.importEntries(entries(12, "imp"), ImportMode.ADD).isSuccess)

        assertEquals(20, count())
        assertTrue(titles().contains("imp5"))
        assertFalse(titles().contains("imp6"))
    }

    @Test
    fun `restoring an oversized backup keeps the first entries and truncates the rest`() = runTest {
        val oversized = VaultDocument(entries = entries(31, "b"))
        assertTrue(repo.replaceVaultWithDocument(master.copyOf(), answers, oversized).isSuccess)

        assertEquals(20, count())
        assertEquals("b1", titles().first())
        assertEquals("b20", titles().last())
        assertFalse(titles().contains("b21"))
    }

    @Test
    fun `a higher tier restores more of the same backup`() = runTest {
        policy.tier = SubscriptionTier.PLUS
        val oversized = VaultDocument(entries = entries(31, "b"))
        assertTrue(repo.replaceVaultWithDocument(master.copyOf(), answers, oversized).isSuccess)
        assertEquals("all 31 fit under the 100-entry cap", 31, count())
    }

    // -- §46O: entitlement is not a route into the vault ---------------------

    @Test
    fun `capacity never affects unlocking`() = runTest {
        repo.createVault(master.copyOf(), answers)
        policy.tier = SubscriptionTier.PRO
        assertTrue(repo.importEntries(entries(300, "x"), ImportMode.REPLACE).isSuccess)
        repo.lock()

        // Capacity collapses while locked. Unlock must be entirely unaffected:
        // the tier controls capacity, never cryptographic access.
        policy.tier = SubscriptionTier.FREE
        assertTrue(repo.unlockWithMaster(master.copyOf()).isSuccess)
        assertEquals(300, count())

        // ...and a wrong password still fails, at any tier.
        repo.lock()
        policy.tier = SubscriptionTier.UNLIMITED
        assertTrue(repo.unlockWithMaster("wrong-password-99".toCharArray()).isFailure)
    }

    @Test
    fun `a vault with no capacity policy is unlimited`() = runTest {
        // §46P.13/14: the vault is complete without the monetization layer.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "vault").deleteRecursively()
        val plain = VaultRepository(VaultFileStore(ctx))
        assertTrue(plain.createVault(master.copyOf(), answers).isSuccess)
        assertTrue(plain.importEntries(entries(300, "n"), ImportMode.REPLACE).isSuccess)
        assertEquals(300, (plain.state.value as VaultState.Unlocked).entries.size)
    }
}
