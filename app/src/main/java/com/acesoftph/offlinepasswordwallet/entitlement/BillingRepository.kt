package com.acesoftph.offlinepasswordwallet.entitlement

import android.app.Activity

/**
 * Result of asking the store what the user owns.
 *
 * Deliberately a closed set of plain values with no Google Play types in it, so
 * that the Play Billing library stays behind exactly one implementation of
 * [BillingRepository] and nothing above it (§46C).
 */
sealed interface BillingStatus {

    /**
     * No store to ask: Play Services is absent, the device cannot reach Play, or
     * the app was installed from outside Play.
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
 * One tier as the store describes it, with the price Play will actually charge.
 *
 * [formattedPrice] is Play's own localized string — "₱299.00", "$4.99" — and is
 * the only price a user should ever be shown (§46J). The peso figures in
 * [ProductCatalog] are planning values and are not a substitute for this.
 */
data class StoreProduct(
    val tier: SubscriptionTier,
    val formattedPrice: String,
)

/** What came back from launching a purchase. */
sealed interface PurchaseResult {

    /** Purchase completed and the entitlement is now at least [tier]. */
    data class Success(val tier: SubscriptionTier) : PurchaseResult

    /** The user backed out. Not an error, and nothing should be said about it. */
    data object Cancelled : PurchaseResult

    /**
     * Play accepted the purchase but has not settled it yet — a cash or
     * bank-transfer payment, typically. No entitlement is granted now; it
     * arrives on a later query once Play settles it.
     */
    data object Pending : PurchaseResult

    /** Already owned. Treated as success by the caller after a re-query. */
    data object AlreadyOwned : PurchaseResult

    /** Anything else, with a message safe to show. */
    data class Failed(val reason: String) : PurchaseResult
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
 * Implementations must never throw: every failure is a value.
 */
interface BillingRepository {

    /**
     * Whether a store is present to talk to at all.
     *
     * False until a connection has actually succeeded, so the UI offers to sell
     * something only once it knows it can. It can go false again if Play
     * disappears mid-session.
     */
    val isAvailable: Boolean

    /**
     * Highest tier the user currently owns, used both at startup and for
     * explicit "restore purchases" (§46E). One-time products are restored by
     * re-querying, so this is the whole restoration mechanism.
     */
    suspend fun queryOwnedTier(): BillingStatus

    /**
     * Play's localized prices for the paid tiers, for display only (§46J).
     * Empty when the store cannot be reached; the UI then falls back to
     * [ProductCatalog]'s planning prices, clearly labelled as indicative.
     */
    suspend fun queryProducts(): List<StoreProduct>

    /**
     * Runs Play's purchase flow for [tier] and suspends until it resolves.
     *
     * Needs an [Activity] because Play's sheet is an activity result; that is a
     * Play constraint, not a design choice. The vault is not involved and is not
     * touched — a purchase changes capacity, never data or keys (§46I, §46O).
     */
    suspend fun launchPurchase(activity: Activity, tier: SubscriptionTier): PurchaseResult
}

/**
 * A [BillingRepository] for builds with no store: reports nothing available and
 * sells nothing.
 *
 * Kept after real billing shipped because it is still the honest answer in unit
 * tests and on any build where monetization is deliberately absent. The app is a
 * complete offline password manager with this wired in (§46D, §46P.14).
 */
class NoBillingRepository : BillingRepository {
    override val isAvailable: Boolean = false
    override suspend fun queryOwnedTier(): BillingStatus = BillingStatus.Unavailable
    override suspend fun queryProducts(): List<StoreProduct> = emptyList()
    override suspend fun launchPurchase(activity: Activity, tier: SubscriptionTier): PurchaseResult =
        PurchaseResult.Failed("This build has no store to buy from.")
}

/**
 * One owned product, reduced to the three things the tier decision depends on.
 *
 * This exists so [highestOwnedTier] is a pure function over plain values that a
 * JVM test can call directly. Mapping Play's `Purchase` onto it is the only
 * place the billing types are touched.
 */
internal data class OwnedProduct(
    val productId: String,
    val isPurchased: Boolean,
    val isAcknowledged: Boolean,
)

/**
 * The tier a set of owned products adds up to (§46E.2).
 *
 * Only settled purchases count: a PENDING one — cash at a convenience store, a
 * bank transfer Play has not cleared — is explicitly not an entitlement yet, and
 * granting on it would hand out capacity for money that may never arrive.
 *
 * Unrecognized product ids are ignored rather than treated as an error. A user
 * may legitimately own a product from a future version, or one we have retired,
 * and neither should cost them the tier they did buy.
 *
 * Acknowledgement is deliberately NOT a condition. Play refunds a purchase that
 * goes unacknowledged for three days, but until that happens the user has paid
 * and must have what they paid for; the acknowledgement itself is fired off
 * separately.
 */
internal fun highestOwnedTier(owned: List<OwnedProduct>): SubscriptionTier =
    SubscriptionTier.highestOf(
        owned.filter { it.isPurchased }.mapNotNull { ProductCatalog.tierForProductId(it.productId) },
    )
