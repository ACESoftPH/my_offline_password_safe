package com.acesoftph.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.entitlement.ProductCatalog
import com.acesoftph.offlinepasswordwallet.entitlement.PurchaseResult
import com.acesoftph.offlinepasswordwallet.entitlement.SubscriptionTier
import com.acesoftph.offlinepasswordwallet.entitlement.TierProduct
import com.acesoftph.offlinepasswordwallet.ui.components.SectionHeader
import com.acesoftph.offlinepasswordwallet.ui.components.WalletCard
import com.acesoftph.offlinepasswordwallet.ui.components.tierTitle
import com.acesoftph.offlinepasswordwallet.ui.theme.LocalWalletPalette
import kotlinx.coroutines.launch

/**
 * The upgrade screen (§46K).
 *
 * Every tier is listed with its capacity and price, together with the two
 * statements §46K requires: that these are one-time purchases rather than a
 * subscription, and that the vault stays local and encrypted regardless.
 *
 * ## Prices
 *
 * Play's own localized price is used wherever the store gives us one (§46J).
 * [ProductCatalog]'s peso figures are the fallback for a device that cannot
 * reach Play, and are labelled as indicative when shown — they are planning
 * values, and presenting one as final to someone who will be charged in another
 * currency would be a lie.
 *
 * ## Upgrading
 *
 * One-time products have no proration mechanism in Play, so upgrading means
 * buying the higher product and keeping the lower one. The screen says so
 * outright above the buttons, because a user who expects the difference to be
 * credited would otherwise find out only from their receipt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(activity: FragmentActivity?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entitlement = ServiceLocator.entitlementManager
    val billing = ServiceLocator.billingRepository
    val currentTier by entitlement.tier.collectAsStateWithLifecycle()

    var storePrices by remember { mutableStateOf<Map<SubscriptionTier, String>>(emptyMap()) }
    var storeReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Connecting to Play and fetching prices is the first thing that touches the
    // network in this app's life. It is deliberately confined to this screen: a
    // user who never opens Upgrade never causes a single connection.
    LaunchedEffect(Unit) {
        val products = billing.queryProducts()
        storePrices = products.associate { it.tier to it.formattedPrice }
        storeReady = billing.isAvailable
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tierTitle("Upgrade")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "More capacity, one payment",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "One-time purchase. No subscription. Your vault remains stored " +
                    "locally and encrypted on this device, on every tier.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Plans")

            ProductCatalog.all.forEach { product ->
                TierCard(
                    product = product,
                    storePrice = storePrices[product.tier],
                    isCurrent = product.tier == currentTier,
                    isRecommended = product.tier == ProductCatalog.recommended,
                    canBuy = product.tier.ordinal > currentTier.ordinal,
                    enabled = storeReady && activity != null && !busy,
                    onBuy = {
                        if (activity == null) return@TierCard
                        scope.launch {
                            busy = true
                            val result = billing.launchPurchase(activity, product.tier)
                            // Play is the source of truth, so the entitlement is
                            // re-derived from a fresh query rather than assumed
                            // from the purchase result (§46E).
                            if (result is PurchaseResult.Success ||
                                result is PurchaseResult.AlreadyOwned
                            ) {
                                entitlement.refreshFromBilling()
                            }
                            busy = false
                            result.message(product.tier)?.let { snackbar.showSnackbar(it) }
                        }
                    },
                )
            }

            Text(
                if (storePrices.isEmpty()) {
                    "Prices shown are indicative. Google Play shows the final price " +
                        "in your own currency before you confirm anything."
                } else {
                    "Prices come from Google Play and are what you will be charged."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Upgrading changes capacity only. Your entries, master password, " +
                    "security answers and backups are untouched, and you never need " +
                    "to recreate your vault.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Each plan is bought outright. Moving up later means buying the " +
                    "higher plan — Google Play does not credit the one you already own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        val tier = entitlement.refreshFromBilling()
                        storeReady = billing.isAvailable
                        busy = false
                        snackbar.showSnackbar(
                            if (billing.isAvailable) {
                                "Purchases restored. Current plan: ${tier.displayName}."
                            } else {
                                "Google Play is not reachable, so there is nothing to " +
                                    "restore right now. Current plan: ${tier.displayName}."
                            },
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp)
                    .testTag("restore_purchases"),
            ) { Text("Restore purchases") }
        }
    }
}

/** What to tell the user, or null when the outcome speaks for itself. */
private fun PurchaseResult.message(tier: SubscriptionTier): String? = when (this) {
    is PurchaseResult.Success -> "Thank you. Your plan is now ${this.tier.displayName}."
    // Backing out of a payment sheet is a decision, not an event worth narrating.
    PurchaseResult.Cancelled -> null
    PurchaseResult.Pending ->
        "Google Play is still processing that payment. Your plan updates as soon " +
            "as it clears — your vault is unaffected in the meantime."
    PurchaseResult.AlreadyOwned -> "You already own ${tier.displayName}. Plan restored."
    is PurchaseResult.Failed -> reason
}

@Composable
private fun TierCard(
    product: TierProduct,
    storePrice: String?,
    isCurrent: Boolean,
    isRecommended: Boolean,
    canBuy: Boolean,
    enabled: Boolean,
    onBuy: () -> Unit,
) {
    val palette = LocalWalletPalette.current
    WalletCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    product.tier.displayName.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isRecommended) {
                    Text(
                        "  ·  Most Popular",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                }
                if (isCurrent) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(
                            "Your plan",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                        )
                    }
                }
            }
            Text(
                product.capacityLabel,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                storePrice?.let { "$it one-time" } ?: product.priceLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (canBuy) {
                Button(
                    onClick = onBuy,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("buy_${product.tier.name.lowercase()}"),
                ) {
                    Text(
                        if (enabled) {
                            "Upgrade to ${product.tier.displayName}"
                        } else {
                            "Unavailable right now"
                        },
                    )
                }
            }
        }
    }
}
