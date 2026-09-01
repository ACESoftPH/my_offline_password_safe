package com.acesoftph.offlinepasswordwallet.entitlement

/**
 * Result of asking the store what the user owns.
 *
 * Deliberately a closed set of plain values with no Google Play types in it, so
 * that adding the Play Billing library later changes exactly one implementation
 * of [BillingRepository] and nothing above it (§46C).
 */
sealed interface BillingStatus {

    /**
     * No store to ask: billing is not compiled in, Play Services is absent, the
     * device is offline, or the user installed from outside Play.
     *
     * This is a normal state, not an error. The app is a fully functional
     * offline password manager in it (§46D, §46P.14).
     */
    data object Unavailable : BillingStatus

    /** The store answered. [tier] is the highest tier owned, possibly FREE. */
    data class Owned(val tier: SubscriptionTier) : BillingStatus

    /**
     * The store was reachable but the query failed. Distinct from [Unavailable]
     * because it may be worth retrying; neither ever blocks the vault (§46P.13).
     */
    data class Failed(val reason: String) : BillingStatus
}

/**
 * The only thing that knows how purchases are obtained (§46C).
 *
 *     UI -> EntitlementManager -> BillingRepository -> Google Play Billing
 *
 * Nothing above this interface may import a billing API. The vault, the crypto
 * and the auth layers do not depend on it at all and never call it, so billing
 * cannot fail in a way that costs a user access to their passwords (§46A, §46B).
 *
 * Implementations must never throw: every failure is a [BillingStatus] value.
 */
interface BillingRepository {

    /** Whether a store is present to talk to at all. */
    val isAvailable: Boolean

    /**
     * Highest tier the user currently owns, used both at startup and for
     * explicit "restore purchases" (§46E). One-time products are restored by
     * re-querying, so this is the whole restoration mechanism.
     */
    suspend fun queryOwnedTier(): BillingStatus
}

/**
 * The billing implementation used while billing is not wired up (§46D).
 *
 * Reports no store and answers nothing, which defaults every install to FREE and
 * keeps the app completely offline. This is what ships today: the Play Billing
 * library is deliberately NOT a dependency yet, because adding it introduces
 * `com.android.vending.BILLING` and would need the "no internet permission"
 * claim in the store listing re-verified first. Swapping this for a real
 * implementation is a one-line change in ServiceLocator.
 */
class NoBillingRepository : BillingRepository {
    override val isAvailable: Boolean = false
    override suspend fun queryOwnedTier(): BillingStatus = BillingStatus.Unavailable
}
