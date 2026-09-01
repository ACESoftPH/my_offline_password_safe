package com.acesoftph.offlinepasswordwallet.data.repository

/**
 * How many entries the vault may hold, from the vault's point of view.
 *
 * This exists so [VaultRepository] can enforce a capacity without knowing that
 * tiers, products or Google Play exist (§46B). The entitlement layer supplies an
 * implementation; the vault only ever asks for a number and a sentence to show
 * when it is reached.
 *
 * The default is [UnlimitedEntryCapacity], so a vault constructed without an
 * entitlement -- in tests, or in any future context where monetization is not
 * involved -- is fully functional. The vault is never broken by the absence of
 * the billing side of the app (§46A, §46P.13).
 */
interface EntryCapacityPolicy {

    /** Maximum entries allowed right now. */
    fun maxEntries(): Int

    /** User-facing explanation shown when an add is refused (§46G). */
    fun capacityMessage(): String
}

/** No limit. The default, and what the vault uses when nothing else is wired in. */
object UnlimitedEntryCapacity : EntryCapacityPolicy {
    override fun maxEntries(): Int = Int.MAX_VALUE
    override fun capacityMessage(): String = "This vault has no entry limit."
}

/**
 * Thrown when creating an entry would exceed the current capacity.
 *
 * Only ever raised by the *creation* paths. Reading, editing and deleting are
 * never refused, so this can never stand between a user and data they already
 * have (§46H).
 */
class EntryCapacityReachedException(message: String) : Exception(message)
