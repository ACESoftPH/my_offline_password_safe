package com.acesoftph.offlinepasswordwallet.entitlement

/**
 * A development-only way to pretend to own a tier, so the higher capacities can
 * be exercised before billing exists (§46D).
 *
 * §46D and §46P.1 require that this is not merely inert in release builds but
 * absent from them. That is enforced by build variant rather than by a runtime
 * `if (BuildConfig.DEBUG)` check: the two implementations live in `src/debug`
 * and `src/release`, so the debug one is never compiled into a release APK and
 * cannot be reached by flipping a flag, decompiling, or hooking. A runtime check
 * would leave the code sitting in the shipped binary.
 */
interface EntitlementOverride {

    /** Whether overriding is possible in this build. False in release. */
    val isSupported: Boolean

    /** The forced tier, or null to defer to the real entitlement. */
    fun overriddenTier(): SubscriptionTier?

    /** Sets or clears the forced tier. A no-op where unsupported. */
    fun setOverride(tier: SubscriptionTier?)
}

/**
 * The release behaviour: no override, ever.
 *
 * Also used anywhere a test needs the production path.
 */
object NoEntitlementOverride : EntitlementOverride {
    override val isSupported: Boolean = false
    override fun overriddenTier(): SubscriptionTier? = null
    override fun setOverride(tier: SubscriptionTier?) = Unit
}
