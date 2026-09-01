package com.acesoftph.offlinepasswordwallet.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place that answers "what is this user allowed to do?" (§46A.2).
 *
 * Tier logic lives here and nowhere else. UI screens and the vault repository
 * ask this class rather than comparing counts against constants of their own, so
 * changing a capacity or adding a tier touches [ProductCatalog] and this file
 * only (§46A.2, §46J).
 *
 * ## Where the tier comes from
 *
 * In priority order:
 *
 *  1. A debug override, in debug builds only (§46D) -- absent from release.
 *  2. Google Play, when a store is reachable (§46E). Authoritative when it
 *     answers, and the answer is cached.
 *  3. The integrity-protected local cache of Play's last answer (§46F), so a
 *     paying user keeps their capacity while offline.
 *  4. FREE.
 *
 * Play answering "you own nothing" is authoritative and downgrades the cache;
 * Play being *unreachable* is not an answer and leaves the cache alone. Without
 * that distinction every offline launch would silently demote a paying user.
 *
 * ## Deliberately not coupled to the vault
 *
 * The entry count is a parameter rather than something this class reads from the
 * vault (§46B). It therefore holds no vault reference, cannot keep decrypted
 * data alive, and works identically while the vault is locked. Nothing here can
 * fail in a way that costs access to passwords: the vault never calls it on the
 * unlock path (§46O, §46P.13).
 */
class EntitlementManager(
    private val store: EntitlementStore,
    private val billing: BillingRepository,
    private val override: EntitlementOverride = NoEntitlementOverride,
) {

    private val _tier = MutableStateFlow(SubscriptionTier.FREE)

    /** Observable current tier, for UI that must react to a restore or upgrade. */
    val tier: StateFlow<SubscriptionTier> = _tier.asStateFlow()

    /**
     * Loads the cached tier. Synchronous, and safe to call before unlock.
     *
     * Free of Keystore work only until something has actually been cached; after
     * a purchase this reads the cache, which means a Keystore round-trip. It is
     * still called synchronously at startup, on purpose: doing it in the
     * background would start every cold start on Free and visibly demote a
     * paying user for a frame -- retitling screens and, worse, briefly handing
     * the vault a 20-entry capacity policy -- before flipping back.
     */
    fun load() {
        _tier.value = override.overriddenTier() ?: store.readCachedTier()
    }

    // -------------------------------------------------------------------------
    // The questions the rest of the app asks (§46A.2)
    // -------------------------------------------------------------------------

    fun getCurrentTier(): SubscriptionTier = override.overriddenTier() ?: _tier.value

    fun getMaximumEntries(): Int = ProductCatalog.maxEntriesFor(getCurrentTier())

    fun isUnlimited(): Boolean = getCurrentTier().isUnlimited

    /**
     * Whether another entry may be created, given how many exist now.
     *
     * Note this is `<`, not `<=`: at exactly the limit the answer is no.
     */
    fun canCreateEntry(currentEntryCount: Int): Boolean =
        currentEntryCount < getMaximumEntries()

    /**
     * Spare capacity. Never negative -- a vault that is over capacity after a
     * downgrade reports 0, not a negative number, so callers that use this to
     * size an import cannot compute a nonsense value (§46H).
     */
    fun remainingEntryCapacity(currentEntryCount: Int): Int =
        (getMaximumEntries() - currentEntryCount).coerceAtLeast(0)

    /**
     * True when the vault holds more than the current tier allows, which happens
     * legitimately when an entitlement is lost or a larger backup is restored.
     *
     * This is a state to explain, never to correct: entries are never deleted,
     * hidden or truncated because of it (§46H).
     */
    fun isOverCapacity(currentEntryCount: Int): Boolean =
        currentEntryCount > getMaximumEntries()

    /** The tier a user would move to next, or null at the top (§46I). */
    fun nextTierUp(): TierProduct? = ProductCatalog.nextTierAbove(getCurrentTier())

    /** Message for the capacity-reached prompt (§46G). */
    fun capacityMessage(): String {
        val current = ProductCatalog.productFor(getCurrentTier())
        val next = nextTierUp()
        val head = "Your ${current.tier.displayName} vault supports up to " +
            "${"%,d".format(current.maxEntries)} entries."
        return if (next == null) head else {
            "$head Upgrade to ${next.tier.displayName} for up to " +
                "${if (next.isUnlimited) "unlimited" else "%,d".format(next.maxEntries)} entries."
        }
    }

    // -------------------------------------------------------------------------
    // Purchase restoration (§46E)
    // -------------------------------------------------------------------------

    /**
     * Reconciles the local tier with the store, for startup and for an explicit
     * "restore purchases". One-time products are restored simply by re-querying,
     * so this is the whole restoration mechanism (§46E).
     *
     * Returns the tier in effect afterwards. Never throws: an unreachable or
     * failing store leaves the cached entitlement exactly as it was, because
     * losing network must not cost a paying user their capacity (§46F).
     */
    suspend fun refreshFromBilling(): SubscriptionTier {
        val status = runCatching { billing.queryOwnedTier() }
            .getOrElse { BillingStatus.Failed(it.message ?: "Billing query failed.") }

        when (status) {
            is BillingStatus.Owned -> {
                // Play has spoken, including when it says FREE -- a refund or a
                // purchase made on another account must be able to take a tier
                // away, or the cache could never be corrected downwards.
                //
                // The override still wins over Play in the flow, exactly as it
                // does in load(). Writing status.tier straight into _tier would
                // let a debug build show "Pro" in every title bar while
                // getCurrentTier() -- and therefore every capacity check -- still
                // returned the forced tier.
                store.writeCachedTier(status.tier)
                _tier.value = override.overriddenTier() ?: status.tier
            }
            // Not an answer, so there is nothing to reconcile: keep what we have.
            //
            // Deliberately NOT load(). Re-reading the cache here would look like
            // a no-op but is not: the store fails closed to FREE on any problem
            // -- a Keystore key invalidated by a device restore, a transient
            // getEntry() failure -- so a user who was PRO a moment ago would be
            // silently downgraded by the very branch whose job is to leave them
            // alone (§46F). An unreachable store must cost nothing at all.
            BillingStatus.Unavailable, is BillingStatus.Failed -> Unit
        }
        return getCurrentTier()
    }

    // -------------------------------------------------------------------------
    // Debug only (§46D)
    // -------------------------------------------------------------------------

    /** Whether this build can fake a tier. False in release. */
    val supportsDebugOverride: Boolean get() = override.isSupported

    /** The tier currently being forced, or null when none is. Always null in release. */
    fun debugTier(): SubscriptionTier? = override.overriddenTier()

    /** Forces a tier in debug builds, or clears the override with null; a no-op in release. */
    fun setDebugTier(tier: SubscriptionTier?) {
        override.setOverride(tier)
        load()
    }
}
