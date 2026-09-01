package com.acesoftph.offlinepasswordwallet.di

import com.acesoftph.offlinepasswordwallet.entitlement.BillingRepository
import com.acesoftph.offlinepasswordwallet.entitlement.BillingStatus
import com.acesoftph.offlinepasswordwallet.entitlement.EntitlementManager
import com.acesoftph.offlinepasswordwallet.entitlement.EntitlementStore
import com.acesoftph.offlinepasswordwallet.entitlement.PurchaseResult
import com.acesoftph.offlinepasswordwallet.entitlement.StoreProduct
import com.acesoftph.offlinepasswordwallet.entitlement.SubscriptionTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The startup half of purchase restoration (§46E).
 *
 * [EntitlementManager.refreshFromBilling] was already correct and already
 * reachable from the Upgrade screen's "Restore purchases" button, but nothing in
 * production called it at startup, so a reinstall stayed on the FREE fallback
 * until the user found that button by hand. This exercises the real wiring
 * function, [ServiceLocator.reconcileEntitlement], rather than the singleton.
 *
 * The other half of the contract matters just as much: an unreachable store is
 * not an answer, so startup must never be able to *downgrade* a paying user
 * (§46F).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementWiringTest {

    private class FakeStore(var cached: SubscriptionTier = SubscriptionTier.FREE) : EntitlementStore {
        override fun readCachedTier() = cached
        override fun writeCachedTier(tier: SubscriptionTier) { cached = tier }
        override fun clear() { cached = SubscriptionTier.FREE }
    }

    private class FakeBilling(
        private val status: BillingStatus,
        override val isAvailable: Boolean = true,
        private val throws: Boolean = false,
    ) : BillingRepository {
        var queries = 0
        override suspend fun queryOwnedTier(): BillingStatus {
            queries++
            if (throws) error("store exploded")
            return status
        }

        override suspend fun queryProducts(): List<StoreProduct> = emptyList()
        override suspend fun launchPurchase(
            activity: android.app.Activity,
            tier: SubscriptionTier,
        ): PurchaseResult = PurchaseResult.Failed("not used in this test")
    }

    @Test
    fun `startup queries the store and adopts what the user owns`() = runTest {
        val store = FakeStore(SubscriptionTier.FREE)
        val billing = FakeBilling(BillingStatus.Owned(SubscriptionTier.PRO))
        val manager = EntitlementManager(store, billing).apply { load() }
        assertEquals("starts on the cached tier", SubscriptionTier.FREE, manager.getCurrentTier())

        ServiceLocator.reconcileEntitlement(backgroundScope, manager)
        runCurrent()

        assertEquals("startup must re-query the store", 1, billing.queries)
        assertEquals(SubscriptionTier.PRO, manager.getCurrentTier())
        assertEquals("and cache it for the next offline launch", SubscriptionTier.PRO, store.cached)
    }

    @Test
    fun `an unreachable store at startup leaves a paid tier intact`() = runTest {
        val store = FakeStore(SubscriptionTier.ULTIMATE)
        val manager = EntitlementManager(
            store,
            FakeBilling(BillingStatus.Unavailable, isAvailable = false),
        ).apply { load() }

        ServiceLocator.reconcileEntitlement(backgroundScope, manager)
        runCurrent()

        assertEquals(
            "no network is not an answer and must not cost capacity",
            SubscriptionTier.ULTIMATE,
            manager.getCurrentTier(),
        )
        assertEquals(SubscriptionTier.ULTIMATE, store.cached)
    }

    @Test
    fun `a store that throws at startup neither crashes nor downgrades`() = runTest {
        val store = FakeStore(SubscriptionTier.PLUS)
        val manager = EntitlementManager(store, FakeBilling(BillingStatus.Unavailable, throws = true))
            .apply { load() }

        val job = ServiceLocator.reconcileEntitlement(backgroundScope, manager)
        runCurrent()

        assertEquals("billing must not take the startup coroutine down", false, job.isCancelled)
        assertEquals(SubscriptionTier.PLUS, manager.getCurrentTier())
        assertEquals(SubscriptionTier.PLUS, store.cached)
    }
}
