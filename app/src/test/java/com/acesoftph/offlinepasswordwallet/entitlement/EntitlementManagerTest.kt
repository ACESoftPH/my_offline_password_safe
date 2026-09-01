package com.acesoftph.offlinepasswordwallet.entitlement

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entitlement layer in isolation: no Android, no vault, no store.
 *
 * That this file needs none of those is the point of §46B -- the tier logic has
 * no dependency on vault data, key material or Google Play.
 */
class EntitlementManagerTest {

    /** An in-memory stand-in for the Keystore-backed cache. */
    private class FakeStore(var cached: SubscriptionTier = SubscriptionTier.FREE) : EntitlementStore {
        var writes = 0
        override fun readCachedTier() = cached
        override fun writeCachedTier(tier: SubscriptionTier) { cached = tier; writes++ }
        override fun clear() { cached = SubscriptionTier.FREE }
    }

    /**
     * A store that fails closed to FREE on read, which is exactly what
     * [KeystoreEntitlementStore] does when its Keystore key is unavailable --
     * after a device restore, or transiently. [FakeStore] never fails, so it
     * cannot catch a reload that silently demotes a paying user.
     */
    private class FailingReadStore(var cached: SubscriptionTier) : EntitlementStore {
        var failReads = false
        override fun readCachedTier() =
            if (failReads) SubscriptionTier.FREE else cached
        override fun writeCachedTier(tier: SubscriptionTier) { cached = tier }
        override fun clear() { cached = SubscriptionTier.FREE }
    }

    private class FakeBilling(
        private val status: BillingStatus,
        override val isAvailable: Boolean = true,
        private val throws: Boolean = false,
    ) : BillingRepository {
        override suspend fun queryOwnedTier(): BillingStatus {
            if (throws) error("store exploded")
            return status
        }

        override suspend fun queryProducts(): List<StoreProduct> = emptyList()
        override suspend fun launchPurchase(
            activity: android.app.Activity,
            tier: SubscriptionTier,
        ): PurchaseResult = PurchaseResult.Failed("not used in this test")
    }

    /** A store whose answer can change mid-session, as a real one's does. */
    private class MutableBilling(var status: BillingStatus) : BillingRepository {
        override val isAvailable = true
        override suspend fun queryOwnedTier(): BillingStatus = status

        override suspend fun queryProducts(): List<StoreProduct> = emptyList()
        override suspend fun launchPurchase(
            activity: android.app.Activity,
            tier: SubscriptionTier,
        ): PurchaseResult = PurchaseResult.Failed("not used in this test")
    }

    private class FakeOverride(private var tier: SubscriptionTier?) : EntitlementOverride {
        override val isSupported = true
        override fun overriddenTier() = tier
        override fun setOverride(tier: SubscriptionTier?) { this.tier = tier }
    }

    private fun manager(
        store: FakeStore = FakeStore(),
        billing: BillingRepository = FakeBilling(BillingStatus.Unavailable, isAvailable = false),
        override: EntitlementOverride = NoEntitlementOverride,
    ) = EntitlementManager(store, billing, override).also { it.load() }

    // -- §46A.1 / §46A.3 capacities ------------------------------------------

    @Test
    fun `each tier has the capacity the specification requires`() {
        assertEquals(20, ProductCatalog.maxEntriesFor(SubscriptionTier.FREE))
        assertEquals(100, ProductCatalog.maxEntriesFor(SubscriptionTier.PLUS))
        assertEquals(500, ProductCatalog.maxEntriesFor(SubscriptionTier.PRO))
        assertEquals(1_000, ProductCatalog.maxEntriesFor(SubscriptionTier.ULTIMATE))
        assertTrue(ProductCatalog.maxEntriesFor(SubscriptionTier.UNLIMITED) >= Int.MAX_VALUE)
    }

    @Test
    fun `product ids are distinct and configurable in one place`() {
        val ids = ProductCatalog.all.map { it.productId }
        assertEquals("no duplicate product ids", ids.size, ids.toSet().size)
        assertEquals(SubscriptionTier.PRO, ProductCatalog.tierForProductId("locknest_pro"))
        assertNull("an unknown id must not map to a tier", ProductCatalog.tierForProductId("nope"))
    }

    // -- §46P.6-10 the boundary at every tier --------------------------------

    @Test
    fun `the last allowed entry and the first refused one, per tier`() {
        val expected = mapOf(
            SubscriptionTier.FREE to 20,
            SubscriptionTier.PLUS to 100,
            SubscriptionTier.PRO to 500,
            SubscriptionTier.ULTIMATE to 1_000,
        )
        expected.forEach { (tier, max) ->
            val m = manager(FakeStore(tier))
            assertTrue("$tier must allow entry #$max", m.canCreateEntry(max - 1))
            assertFalse("$tier must refuse entry #${max + 1}", m.canCreateEntry(max))
            assertEquals(1, m.remainingEntryCapacity(max - 1))
            assertEquals(0, m.remainingEntryCapacity(max))
        }
    }

    @Test
    fun `unlimited has no artificial limit`() {
        val m = manager(FakeStore(SubscriptionTier.UNLIMITED))
        assertTrue(m.isUnlimited())
        assertTrue(m.canCreateEntry(0))
        assertTrue(m.canCreateEntry(50_000))
        assertTrue(m.canCreateEntry(Int.MAX_VALUE - 2))
    }

    @Test
    fun `remaining capacity never goes negative`() {
        val m = manager(FakeStore(SubscriptionTier.FREE))
        assertEquals(0, m.remainingEntryCapacity(20))
        assertEquals(0, m.remainingEntryCapacity(400))
    }

    // -- §46H downgrade ------------------------------------------------------

    @Test
    fun `a downgraded vault reports over capacity without losing anything`() {
        val store = FakeStore(SubscriptionTier.PRO)
        val m = manager(store)
        assertFalse(m.isOverCapacity(150))

        // Play now says FREE -- a refund, or the purchase was on another account.
        val downgraded = EntitlementManager(
            store, FakeBilling(BillingStatus.Owned(SubscriptionTier.FREE)), NoEntitlementOverride,
        ).also { it.load() }
        runTest { downgraded.refreshFromBilling() }

        assertEquals(SubscriptionTier.FREE, downgraded.getCurrentTier())
        // 150 entries against a 20 cap: over capacity, no new entries...
        assertTrue(downgraded.isOverCapacity(150))
        assertFalse(downgraded.canCreateEntry(150))
        assertEquals(0, downgraded.remainingEntryCapacity(150))
        // ...and nothing in this layer can delete or hide one. It only answers
        // questions; the vault is never told to shed entries (§46H).
    }

    // -- §46E restoration ----------------------------------------------------

    @Test
    fun `a store answer is authoritative and is cached`() = runTest {
        val store = FakeStore(SubscriptionTier.FREE)
        val m = EntitlementManager(store, FakeBilling(BillingStatus.Owned(SubscriptionTier.ULTIMATE)))
        m.load()
        assertEquals(SubscriptionTier.FREE, m.getCurrentTier())

        assertEquals(SubscriptionTier.ULTIMATE, m.refreshFromBilling())
        assertEquals("the answer must be cached for offline use", SubscriptionTier.ULTIMATE, store.cached)
    }

    @Test
    fun `the highest owned tier wins`() {
        val owned = listOf(SubscriptionTier.PLUS, SubscriptionTier.ULTIMATE, SubscriptionTier.PRO)
        assertEquals(SubscriptionTier.ULTIMATE, SubscriptionTier.highestOf(owned))
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.highestOf(emptyList()))
    }

    // -- §46F offline behaviour ----------------------------------------------

    @Test
    fun `an unreachable store leaves a paid tier intact`() = runTest {
        val store = FakeStore(SubscriptionTier.PRO)
        val m = EntitlementManager(store, FakeBilling(BillingStatus.Unavailable, isAvailable = false))
        m.load()

        assertEquals(SubscriptionTier.PRO, m.refreshFromBilling())
        assertEquals(SubscriptionTier.PRO, store.cached)
        assertEquals("nothing should have been written", 0, store.writes)
    }

    @Test
    fun `a failing store leaves a paid tier intact`() = runTest {
        val store = FakeStore(SubscriptionTier.ULTIMATE)
        val m = EntitlementManager(store, FakeBilling(BillingStatus.Failed("timeout")))
        m.load()
        assertEquals(SubscriptionTier.ULTIMATE, m.refreshFromBilling())
    }

    @Test
    fun `a store that throws is contained, not propagated`() = runTest {
        // §46P.13: billing must never be able to take the app down with it.
        val store = FakeStore(SubscriptionTier.PLUS)
        val m = EntitlementManager(store, FakeBilling(BillingStatus.Unavailable, throws = true))
        m.load()
        assertEquals(SubscriptionTier.PLUS, m.refreshFromBilling())
    }

    @Test
    fun `a store that stops answering cannot demote a tier Play already confirmed`() = runTest {
        // The regression: the "not an answer" branch used to re-read the cache.
        // That reads as a no-op and is not -- the real store fails closed to
        // FREE, so a Keystore hiccup on a later refresh would take PRO away from
        // someone Play had already confirmed owns it (§46F).
        val store = FailingReadStore(SubscriptionTier.FREE)
        val billing = MutableBilling(BillingStatus.Owned(SubscriptionTier.PRO))
        val m = EntitlementManager(store, billing).also { it.load() }
        assertEquals(SubscriptionTier.PRO, m.refreshFromBilling())

        // Same session, later: offline, and the Keystore has stopped cooperating.
        store.failReads = true
        billing.status = BillingStatus.Unavailable
        assertEquals(
            "an unreachable store must not undo what Play confirmed",
            SubscriptionTier.PRO,
            m.refreshFromBilling(),
        )

        billing.status = BillingStatus.Failed("timeout")
        assertEquals(
            "nor may a failing one",
            SubscriptionTier.PRO,
            m.refreshFromBilling(),
        )
    }

    @Test
    fun `an unreachable store does not re-read the cache at all`() = runTest {
        val store = FailingReadStore(SubscriptionTier.ULTIMATE)
        val m = EntitlementManager(store, FakeBilling(BillingStatus.Unavailable, isAvailable = false))
        m.load()
        assertEquals(SubscriptionTier.ULTIMATE, m.getCurrentTier())

        store.failReads = true
        assertEquals(
            "a store with no answer must cost nothing, not even a cache read",
            SubscriptionTier.ULTIMATE,
            m.refreshFromBilling(),
        )
    }

    // -- §46D debug override -------------------------------------------------

    @Test
    fun `a debug override still wins after Play answers`() = runTest {
        // Otherwise the tier flow (titles, the Settings plan row) and
        // getCurrentTier() (every capacity check) disagree, and a debug build
        // reads "Pro" over a 20-entry limit.
        val store = FakeStore(SubscriptionTier.FREE)
        val m = EntitlementManager(
            store,
            FakeBilling(BillingStatus.Owned(SubscriptionTier.PRO)),
            FakeOverride(SubscriptionTier.FREE),
        ).also { it.load() }

        m.refreshFromBilling()

        assertEquals(SubscriptionTier.FREE, m.getCurrentTier())
        assertEquals("the flow must agree with getCurrentTier()", SubscriptionTier.FREE, m.tier.value)
        assertEquals("Play's answer is still cached underneath", SubscriptionTier.PRO, store.cached)
    }

    @Test
    fun `a debug override takes precedence and is reversible`() {
        val m = manager(FakeStore(SubscriptionTier.FREE), override = FakeOverride(SubscriptionTier.PRO))
        assertEquals(SubscriptionTier.PRO, m.getCurrentTier())
        assertEquals(500, m.getMaximumEntries())

        m.setDebugTier(null)
        assertEquals(SubscriptionTier.FREE, m.getCurrentTier())
    }

    @Test
    fun `the production override does nothing`() {
        val m = manager(FakeStore(SubscriptionTier.FREE), override = NoEntitlementOverride)
        assertFalse(m.supportsDebugOverride)
        m.setDebugTier(SubscriptionTier.UNLIMITED)
        assertEquals("release builds must not be able to fake a tier",
            SubscriptionTier.FREE, m.getCurrentTier())
    }

    // -- §46G messaging ------------------------------------------------------

    @Test
    fun `the capacity message names the current tier and the next one up`() {
        val m = manager(FakeStore(SubscriptionTier.FREE))
        val msg = m.capacityMessage()
        assertTrue(msg, msg.contains("Free"))
        assertTrue(msg, msg.contains("20"))
        assertTrue(msg, msg.contains("Plus"))
        assertTrue(msg, msg.contains("100"))
    }

    @Test
    fun `the top tier offers no upgrade`() {
        val m = manager(FakeStore(SubscriptionTier.UNLIMITED))
        assertNull(m.nextTierUp())
        assertFalse(m.capacityMessage().contains("Upgrade to"))
    }

    @Test
    fun `upgrade order is strictly increasing in capacity`() {
        val caps = ProductCatalog.all.map { it.maxEntries }
        assertEquals(caps.sorted(), caps)
        assertNotEquals(caps.first(), caps.last())
    }

    // -- §46K presentation ---------------------------------------------------

    @Test
    fun `capacity and price labels read correctly`() {
        assertEquals("20 entries", ProductCatalog.FREE.capacityLabel)
        assertEquals("1,000 entries", ProductCatalog.ULTIMATE.capacityLabel)
        assertEquals("Unlimited entries", ProductCatalog.UNLIMITED.capacityLabel)
        assertEquals("Free", ProductCatalog.FREE.priceLabel)
        assertTrue(ProductCatalog.PRO.priceLabel.contains("one-time"))
        assertFalse(
            "no tier may be presented as a subscription (§46M)",
            ProductCatalog.all.any { it.priceLabel.contains("month", ignoreCase = true) },
        )
    }

    @Test
    fun `a corrupt persisted tier name falls back to free`() {
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.parseOrFree("UNLIMITED_HAHA"))
        assertEquals(SubscriptionTier.FREE, SubscriptionTier.parseOrFree(null))
        assertEquals(SubscriptionTier.PRO, SubscriptionTier.parseOrFree("PRO"))
    }
}
