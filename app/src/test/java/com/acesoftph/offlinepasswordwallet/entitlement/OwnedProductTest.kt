package com.acesoftph.offlinepasswordwallet.entitlement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one piece of billing logic that decides what a user gets (§46E.2).
 *
 * [highestOwnedTier] is deliberately a pure function over plain values so it can
 * be tested without Play, without Android and without a device. The Play types
 * are mapped onto [OwnedProduct] in exactly one place; everything that could get
 * a paying user's tier wrong is here.
 */
class OwnedProductTest {

    private fun purchased(tier: SubscriptionTier, acknowledged: Boolean = true) =
        OwnedProduct(ProductCatalog.productFor(tier).productId, isPurchased = true, isAcknowledged = acknowledged)

    @Test
    fun `owning nothing is Free, not an error`() {
        assertEquals(SubscriptionTier.FREE, highestOwnedTier(emptyList()))
    }

    @Test
    fun `a single purchase grants exactly its tier`() {
        for (tier in SubscriptionTier.entries - SubscriptionTier.FREE) {
            assertEquals(tier, highestOwnedTier(listOf(purchased(tier))))
        }
    }

    @Test
    fun `owning several tiers grants the highest`() {
        // The normal state after an upgrade: Play has no proration for one-time
        // products, so a user who moves up owns both products forever (§46I).
        val owned = listOf(
            purchased(SubscriptionTier.PLUS),
            purchased(SubscriptionTier.ULTIMATE),
            purchased(SubscriptionTier.PRO),
        )
        assertEquals(SubscriptionTier.ULTIMATE, highestOwnedTier(owned))
    }

    @Test
    fun `order of the store's response does not matter`() {
        val ascending = listOf(purchased(SubscriptionTier.PLUS), purchased(SubscriptionTier.PRO))
        assertEquals(highestOwnedTier(ascending), highestOwnedTier(ascending.reversed()))
    }

    @Test
    fun `a pending purchase grants nothing yet`() {
        // Cash or bank transfer that Play has not cleared. Granting here would
        // hand out capacity for money that may never arrive.
        val pending = OwnedProduct(
            ProductCatalog.productFor(SubscriptionTier.ULTIMATE).productId,
            isPurchased = false,
            isAcknowledged = false,
        )
        assertEquals(SubscriptionTier.FREE, highestOwnedTier(listOf(pending)))
    }

    @Test
    fun `a pending upgrade does not take away the tier already settled`() {
        val owned = listOf(
            purchased(SubscriptionTier.PLUS),
            OwnedProduct(
                ProductCatalog.productFor(SubscriptionTier.UNLIMITED).productId,
                isPurchased = false,
                isAcknowledged = false,
            ),
        )
        assertEquals(SubscriptionTier.PLUS, highestOwnedTier(owned))
    }

    @Test
    fun `an unacknowledged purchase still counts`() {
        // The user has paid. Acknowledgement is our obligation to Play, not a
        // condition the user must satisfy before getting what they bought.
        assertEquals(
            SubscriptionTier.PRO,
            highestOwnedTier(listOf(purchased(SubscriptionTier.PRO, acknowledged = false))),
        )
    }

    @Test
    fun `an unknown product id is ignored, not fatal`() {
        // A product from a future version, or one we retired. Neither may cost
        // the user the tier they did buy.
        val owned = listOf(
            OwnedProduct("locknest_from_the_future", isPurchased = true, isAcknowledged = true),
            purchased(SubscriptionTier.PLUS),
        )
        assertEquals(SubscriptionTier.PLUS, highestOwnedTier(owned))
    }

    @Test
    fun `an empty product id is ignored`() {
        // What a malformed Play response maps to. It must not throw inside a
        // billing callback.
        val owned = listOf(OwnedProduct("", isPurchased = true, isAcknowledged = true))
        assertEquals(SubscriptionTier.FREE, highestOwnedTier(owned))
    }

    @Test
    fun `every paid tier in the catalog is buyable and maps back to itself`() {
        // Guards the catalog against a typo in a product id, which would show a
        // plan the store cannot sell.
        for (product in ProductCatalog.all.filter { it.plannedPricePhp != null }) {
            assertEquals(
                "product id ${product.productId} must map back to ${product.tier}",
                product.tier,
                ProductCatalog.tierForProductId(product.productId),
            )
        }
    }
}
