package com.acesoftph.offlinepasswordwallet.entitlement

/**
 * What the user has paid for (§46A.1, §46A.2).
 *
 * A tier controls **capacity only**. It grants no cryptographic access and can
 * never be a route into the vault: encryption, the master password, recovery,
 * biometrics and auto-lock are identical on every tier (§46A.4, §46O). Nothing
 * in this enum is consulted while unlocking or decrypting.
 *
 * The order of the constants is the upgrade order, and [ordinal] is what
 * "highest owned tier" means when reconciling purchases (§46E). Do not reorder
 * them; add new tiers at the end.
 */
enum class SubscriptionTier {
    FREE,
    PLUS,
    PRO,
    ULTIMATE,
    UNLIMITED,
    ;

    val isUnlimited: Boolean get() = this == UNLIMITED

    /** Human-readable name used in titles and the upgrade screen. */
    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** Highest tier in [tiers], or [FREE] if there are none. */
        fun highestOf(tiers: Collection<SubscriptionTier>): SubscriptionTier =
            tiers.maxByOrNull { it.ordinal } ?: FREE

        /** Parses a persisted name, falling back to [FREE] rather than throwing. */
        fun parseOrFree(raw: String?): SubscriptionTier =
            entries.firstOrNull { it.name == raw } ?: FREE
    }
}
