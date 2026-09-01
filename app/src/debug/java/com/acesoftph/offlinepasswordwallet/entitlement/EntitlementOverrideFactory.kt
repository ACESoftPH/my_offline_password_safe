package com.acesoftph.offlinepasswordwallet.entitlement

import android.content.Context

/**
 * DEBUG BUILDS ONLY.
 *
 * This file lives in `src/debug`, so it is not compiled into a release APK at
 * all (§46D, §46P.1). The release variant supplies its own factory of the same
 * name that returns [NoEntitlementOverride].
 *
 * The override is a plain unsigned preference on purpose: it is a development
 * convenience for exercising the higher capacities, and hardening something
 * that does not exist in production would only obscure that.
 */
object EntitlementOverrideFactory {
    fun create(context: Context): EntitlementOverride = DebugEntitlementOverride(context)
}

private class DebugEntitlementOverride(context: Context) : EntitlementOverride {

    private val prefs = context.applicationContext
        .getSharedPreferences("entitlement_debug", Context.MODE_PRIVATE)

    override val isSupported: Boolean = true

    override fun overriddenTier(): SubscriptionTier? =
        prefs.getString(KEY, null)?.let { raw ->
            SubscriptionTier.entries.firstOrNull { it.name == raw }
        }

    override fun setOverride(tier: SubscriptionTier?) {
        prefs.edit().apply {
            if (tier == null) remove(KEY) else putString(KEY, tier.name)
        }.apply()
    }

    private companion object {
        const val KEY = "debug_tier_override"
    }
}
