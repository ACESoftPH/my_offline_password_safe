package com.acesoftph.offlinepasswordwallet.entitlement

/**
 * One tier's product definition (§46J).
 *
 * @param tier            the entitlement this product grants
 * @param productId       the Google Play product ID. A placeholder until the
 *                        products are actually created in Play Console (§46A.1);
 *                        business logic must reference [tier], never this string.
 * @param maxEntries      capacity, or [ProductCatalog.UNLIMITED_ENTRIES]
 * @param plannedPricePhp planning price in PHP, or null for the free tier. This
 *                        is a product-planning value only. Google Play is the
 *                        source of truth for the price actually shown to a user,
 *                        localized to their country (§46J), so this must never
 *                        be presented as a final price.
 */
data class TierProduct(
    val tier: SubscriptionTier,
    val productId: String,
    val maxEntries: Int,
    val plannedPricePhp: String?,
) {
    val isUnlimited: Boolean get() = maxEntries == ProductCatalog.UNLIMITED_ENTRIES

    /** Capacity as shown to a user, e.g. "1,000 entries" or "Unlimited entries". */
    val capacityLabel: String
        get() = if (isUnlimited) "Unlimited entries" else "%,d entries".format(maxEntries)

    /** Price as shown to a user, always flagged as one-time, never as final. */
    val priceLabel: String
        get() = plannedPricePhp?.let { "$it one-time" } ?: "Free"
}

/**
 * The single place tier capacities, product IDs and planning prices are defined
 * (§46A.1, §46J). Nothing else in the app may hard-code an entry limit or a
 * price; ask [EntitlementManager] instead.
 *
 * Product IDs are configurable placeholders. They are deliberately not embedded
 * in business logic, so renaming them before publication is a change to this
 * file alone.
 */
object ProductCatalog {

    /**
     * Capacity sentinel for [SubscriptionTier.UNLIMITED].
     *
     * [Int.MAX_VALUE] rather than a large round number: it makes "no practical
     * limit" (§46A.3) true by construction and keeps every comparison a plain
     * `count >= max` with no special case, so an unlimited vault can never be
     * accidentally capped by arithmetic elsewhere.
     */
    const val UNLIMITED_ENTRIES = Int.MAX_VALUE

    val FREE = TierProduct(SubscriptionTier.FREE, "locknest_free", 20, null)
    val PLUS = TierProduct(SubscriptionTier.PLUS, "locknest_plus", 100, "PHP 299")
    val PRO = TierProduct(SubscriptionTier.PRO, "locknest_pro", 500, "PHP 399")
    val ULTIMATE = TierProduct(SubscriptionTier.ULTIMATE, "locknest_ultimate", 1_000, "PHP 599")
    val UNLIMITED =
        TierProduct(SubscriptionTier.UNLIMITED, "locknest_unlimited", UNLIMITED_ENTRIES, "PHP 799")

    /** Every product, in upgrade order (§46I). */
    val all: List<TierProduct> = listOf(FREE, PLUS, PRO, ULTIMATE, UNLIMITED)

    /** The tier positioned as the recommended one (§46A.6). */
    val recommended: SubscriptionTier = SubscriptionTier.PRO

    fun productFor(tier: SubscriptionTier): TierProduct =
        all.first { it.tier == tier }

    fun maxEntriesFor(tier: SubscriptionTier): Int = productFor(tier).maxEntries

    /** Maps a Play product ID back to its tier, or null if it is not ours. */
    fun tierForProductId(productId: String): SubscriptionTier? =
        all.firstOrNull { it.productId == productId }?.tier

    /** The next tier up from [tier], or null at the top. */
    fun nextTierAbove(tier: SubscriptionTier): TierProduct? =
        all.firstOrNull { it.tier.ordinal > tier.ordinal }
}
