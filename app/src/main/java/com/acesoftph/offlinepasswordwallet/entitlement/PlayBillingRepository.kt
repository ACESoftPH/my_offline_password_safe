package com.acesoftph.offlinepasswordwallet.entitlement

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Google Play Billing, and the only file in the app that imports it (§46C).
 *
 * Everything above [BillingRepository] deals in [SubscriptionTier],
 * [BillingStatus] and [PurchaseResult]; no Play type escapes this file. Swapping
 * back to [NoBillingRepository] remains a one-line change in ServiceLocator.
 *
 * ## Products
 *
 * All tiers are one-time, non-consumed `INAPP` products (§46M). They are never
 * consumed, which is what makes them permanently restorable by re-querying
 * (§46E). No subscription API is used anywhere here.
 *
 * ## Upgrades
 *
 * Play has no proration or replacement mechanism for one-time products — that
 * exists for subscriptions only. So an upgrade is simply buying the higher
 * product; the user then owns both, and [highestOwnedTier] takes the higher
 * (§46I). Nothing is refunded for the lower tier, which is worth saying plainly
 * in the UI rather than hiding.
 *
 * ## What this does NOT do
 *
 * It does not verify purchase signatures. Doing that properly needs a server to
 * hold the key and check the receipt, and this app deliberately has no server
 * (§46A). Verifying in-process against an embedded public key is theatre: the
 * same person who can forge a purchase can patch out the check. The exposure is
 * bounded and non-security: a forged entitlement buys vault *capacity* and
 * nothing else — no key material, no decryption, no access to anyone's data
 * (§46O). This is documented in the README rather than papered over.
 */
class PlayBillingRepository(context: Context) : BillingRepository {

    /**
     * The purchase currently on screen, completed by [purchasesUpdatedListener].
     *
     * Play reports the outcome of a purchase through a client-wide listener
     * rather than to the call that started it, so the flow has to be bridged
     * back to the coroutine that is waiting. One at a time: Play shows one sheet.
     */
    @Volatile
    private var pendingPurchase: CompletableDeferred<PurchaseResult>? = null

    /** Serializes purchase flows, so two taps cannot both own [pendingPurchase]. */
    private val purchaseLock = Mutex()

    @Volatile
    private var connected = false

    override val isAvailable: Boolean get() = connected && client.isReady

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        val waiting = pendingPurchase ?: return@PurchasesUpdatedListener
        waiting.complete(
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val settled = (purchases ?: emptyList()).filter {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                    }
                    settled.forEach(::acknowledgeIfNeeded)
                    val tier = highestOwnedTier(settled.map(Purchase::toOwnedProduct))
                    // OK with nothing settled means Play took the payment but has
                    // not cleared it: entitlement arrives on a later query, not now.
                    if (settled.isEmpty()) PurchaseResult.Pending else PurchaseResult.Success(tier)
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseResult.AlreadyOwned
                else -> PurchaseResult.Failed(result.userMessage())
            },
        )
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        // Required since Billing 6, and it must name the product kinds we sell.
        // Without enableOneTimeProducts() a pending one-time purchase is an error
        // rather than a state we can report.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        // Play drops the service connection routinely (updates, low memory). Let
        // the library reconnect instead of hand-rolling a retry that would be
        // wrong in a way nobody notices until a user cannot restore a purchase.
        .enableAutoServiceReconnection()
        .build()

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Ensures a live connection, returning false if there is no store to reach.
     *
     * Never throws. A device with no Play Services, a sideloaded install or an
     * offline launch all land here as a plain false, which the layer above reads
     * as [BillingStatus.Unavailable] — not as an answer about what is owned, so
     * it can never downgrade anyone (§46F).
     */
    private suspend fun ensureConnected(): Boolean {
        if (client.isReady) {
            connected = true
            return true
        }
        return suspendCoroutine { continuation ->
            var resumed = false
            fun finish(value: Boolean) {
                if (!resumed) {
                    resumed = true
                    connected = value
                    continuation.resume(value)
                }
            }
            runCatching {
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        finish(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }

                    // Only ever a *loss* of connection. With auto-reconnection on,
                    // the library retries; this is not a setup failure and must not
                    // resume a caller that is already past setup.
                    override fun onBillingServiceDisconnected() {
                        connected = false
                    }
                })
            }.onFailure { finish(false) }
        }
    }

    // -------------------------------------------------------------------------
    // Restoration (§46E)
    // -------------------------------------------------------------------------

    override suspend fun queryOwnedTier(): BillingStatus {
        if (!ensureConnected()) return BillingStatus.Unavailable

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        return suspendCoroutine { continuation ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(BillingStatus.Failed(result.userMessage()))
                    return@queryPurchasesAsync
                }
                // Acknowledge anything Play is still waiting on. Unacknowledged
                // purchases are auto-refunded after three days, so this is the
                // difference between a user keeping what they bought and silently
                // losing it -- including for a purchase that completed while the
                // app was closed.
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach(::acknowledgeIfNeeded)

                continuation.resume(
                    BillingStatus.Owned(highestOwnedTier(purchases.map(Purchase::toOwnedProduct))),
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Prices (§46J)
    // -------------------------------------------------------------------------

    override suspend fun queryProducts(): List<StoreProduct> {
        if (!ensureConnected()) return emptyList()

        val paid = ProductCatalog.all.filter { it.plannedPricePhp != null }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                paid.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it.productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()

        return suspendCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // No prices is not an error worth surfacing: the screen falls
                    // back to the planning prices, labelled as indicative.
                    continuation.resume(emptyList())
                    return@queryProductDetailsAsync
                }
                continuation.resume(
                    details.productDetailsList.mapNotNull { product ->
                        val tier = ProductCatalog.tierForProductId(product.productId)
                        val price = product.oneTimePrice()
                        if (tier == null || price == null) null else StoreProduct(tier, price)
                    },
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Purchase
    // -------------------------------------------------------------------------

    override suspend fun launchPurchase(
        activity: Activity,
        tier: SubscriptionTier,
    ): PurchaseResult = purchaseLock.withLock {
        if (!ensureConnected()) return PurchaseResult.Failed("Google Play is not available right now.")

        val product = productDetailsFor(tier)
            ?: return PurchaseResult.Failed(
                "This plan is not available from the store on this device.",
            )

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .apply {
                            // Billing 8 moved one-time products onto the same
                            // offer-token model subscriptions use. A product
                            // created as a plain one-time purchase still reports a
                            // single offer, so take the first when one exists and
                            // omit the token when the product predates the change.
                            product.oneTimeOfferToken()?.let { setOfferToken(it) }
                        }
                        .build(),
                ),
            )
            .build()

        val waiter = CompletableDeferred<PurchaseResult>()
        pendingPurchase = waiter
        try {
            val launch = client.launchBillingFlow(activity, flowParams)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                return if (launch.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    PurchaseResult.AlreadyOwned
                } else {
                    PurchaseResult.Failed(launch.userMessage())
                }
            }
            return waiter.await()
        } finally {
            pendingPurchase = null
        }
    }

    private suspend fun productDetailsFor(tier: SubscriptionTier): ProductDetails? {
        val productId = ProductCatalog.productFor(tier).productId
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()

        return suspendCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { result, details ->
                continuation.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        details.productDetailsList.firstOrNull { it.productId == productId }
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Tells Play the purchase was delivered. Fire-and-forget on purpose.
     *
     * A failure here cannot be usefully surfaced — the user has their capacity
     * either way — and the call is idempotent, so the next startup query retries
     * it well inside Play's three-day refund window.
     */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        runCatching { client.acknowledgePurchase(params) { /* retried on next query */ } }
    }
}

// -----------------------------------------------------------------------------
// Play type -> plain value
// -----------------------------------------------------------------------------

private fun Purchase.toOwnedProduct(): OwnedProduct = OwnedProduct(
    // A Play purchase can name several products; ours never do, but taking the
    // first rather than assuming exactly one keeps a malformed response from
    // throwing inside a billing callback.
    productId = products.firstOrNull().orEmpty(),
    isPurchased = purchaseState == Purchase.PurchaseState.PURCHASED,
    isAcknowledged = isAcknowledged,
)

/** Play's localized price for a one-time product, across both offer shapes. */
private fun ProductDetails.oneTimePrice(): String? =
    oneTimePurchaseOfferDetails?.formattedPrice
        ?: oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice

/** The offer token to buy with, when the product reports one. */
private fun ProductDetails.oneTimeOfferToken(): String? =
    oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken

/**
 * A billing result as something worth showing a user.
 *
 * Play's debug message is written for developers and is occasionally empty, so
 * it is never shown on its own.
 */
private fun BillingResult.userMessage(): String = when (responseCode) {
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
        "Google Play billing is not available on this device."
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
    ->
        "Google Play is not responding. Your vault is unaffected — try again later."
    BillingClient.BillingResponseCode.NETWORK_ERROR ->
        "No connection to Google Play. Your vault works offline as normal."
    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
        "This plan is not available on this account or in this country."
    BillingClient.BillingResponseCode.ITEM_NOT_OWNED ->
        "Google Play has no record of that purchase on this account."
    BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
        "The store is misconfigured for this build. Please report this."
    else -> "Google Play could not complete that request."
}
