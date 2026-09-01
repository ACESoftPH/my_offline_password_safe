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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.entitlement.ProductCatalog
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
 * Prices shown here come from [ProductCatalog] and are **planning** values.
 * Google Play is the source of truth for what a user is actually charged, in
 * their own currency (§46J), so the screen says so rather than presenting a
 * peso figure as final. Once billing is wired up these labels are replaced by
 * the store's own localized prices.
 *
 * Purchasing is not live yet (§46D): the buttons explain that instead of
 * pretending to start a transaction. Showing a working-looking Buy button that
 * silently does nothing would be worse than saying plainly that it is not ready.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entitlement = ServiceLocator.entitlementManager
    val currentTier by entitlement.tier.collectAsStateWithLifecycle()
    val billingLive = ServiceLocator.billingRepository.isAvailable

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
                    isCurrent = product.tier == currentTier,
                    isRecommended = product.tier == ProductCatalog.recommended,
                    canBuy = product.tier.ordinal > currentTier.ordinal,
                    billingLive = billingLive,
                    onBuy = {
                        scope.launch {
                            snackbar.showSnackbar(
                                "In-app purchases are not enabled in this build yet.",
                            )
                        }
                    },
                )
            }

            Text(
                "Prices are indicative. Google Play shows the final price in your " +
                    "own currency before you confirm anything.",
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

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val tier = entitlement.refreshFromBilling()
                        snackbar.showSnackbar(
                            if (billingLive) {
                                "Purchases restored. Current plan: ${tier.displayName}."
                            } else {
                                "No store available, so there is nothing to restore. " +
                                    "Current plan: ${tier.displayName}."
                            },
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp)
                    .testTag("restore_purchases"),
            ) { Text("Restore purchases") }
        }
    }
}

@Composable
private fun TierCard(
    product: TierProduct,
    isCurrent: Boolean,
    isRecommended: Boolean,
    canBuy: Boolean,
    billingLive: Boolean,
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
                product.priceLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (canBuy) {
                Button(
                    onClick = onBuy,
                    enabled = billingLive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("buy_${product.tier.name.lowercase()}"),
                ) {
                    Text(if (billingLive) "Upgrade to ${product.tier.displayName}" else "Coming soon")
                }
            }
        }
    }
}

/** Whether [tier] is worth offering as an upgrade from [from]. */
internal fun isUpgradeFrom(from: SubscriptionTier, tier: SubscriptionTier): Boolean =
    tier.ordinal > from.ordinal
