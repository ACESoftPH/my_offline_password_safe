package com.acesoftph.offlinepasswordwallet.entitlement

import android.content.Context

/**
 * RELEASE BUILDS.
 *
 * The release variant's counterpart to the `src/debug` factory of the same name.
 * There is no override mechanism here to disable, bypass or discover: a release
 * build gets its entitlement only from the real path -- Google Play, and the
 * integrity-protected cache of what Play last said (§46D, §46P.1).
 */
object EntitlementOverrideFactory {
    fun create(context: Context): EntitlementOverride = NoEntitlementOverride
}
