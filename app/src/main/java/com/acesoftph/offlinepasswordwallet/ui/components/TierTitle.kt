package com.acesoftph.offlinepasswordwallet.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator

/**
 * A screen title carrying the current edition, e.g. "Password List - Free" or
 * "Settings - Pro".
 *
 * Screens call this instead of formatting the suffix themselves, so no screen
 * contains tier logic (§46A.2) and all of them change together when an upgrade
 * or a restore lands. It collects the tier as state, so a purchase completed
 * while a screen is open retitles it without a navigation round trip.
 */
@Composable
fun tierTitle(base: String): String {
    val tier by ServiceLocator.entitlementManager.tier.collectAsStateWithLifecycle()
    return "$base - ${tier.displayName}"
}
