package com.acesoftph.offlinepasswordwallet.entitlement

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The offline entitlement cache (§46F).
 *
 * ## Why not a plain preference
 *
 * §46F rules out `tier = "UNLIMITED"` in SharedPreferences as the production
 * mechanism, and rightly: on a rooted device that file is one text edit away
 * from any tier. So the cached tier is stored with an HMAC-SHA256 tag computed
 * over it using a key that lives in the Android Keystore and cannot be read out
 * of the device — not by the app, and not by someone editing files. Editing the
 * stored tier invalidates the tag, the record is rejected, and the entitlement
 * falls back to FREE.
 *
 * The tag covers the tier name **and** a per-install random id, so a record
 * cannot be lifted from one install and dropped into another.
 *
 * ## What this does and does not achieve
 *
 * This raises tampering from "edit a text file" to "defeat a hardware-backed
 * key or patch the app". It is not, and cannot be, unbeatable: the app runs on
 * the user's own hardware, and anyone able to rebuild it can return whatever
 * tier they like. That is an accepted limit of client-side entitlement, which
 * is exactly why Google Play remains the source of truth whenever it can be
 * reached (§46E) and this is only a cache for when it cannot.
 *
 * It is deliberately NOT a security boundary for the vault. A forged tier buys
 * capacity and nothing else — no key material, no decryption, no access to
 * anyone else's data (§46O).
 *
 * ## Failure behaviour
 *
 * Every failure path returns FREE rather than throwing, including a missing or
 * invalidated Keystore key (which happens legitimately after a device restore).
 * Failing closed to FREE can understate what someone paid for; the fix is a
 * re-query against Play, which restores the real tier (§46E). Failing open
 * would hand every tier to anyone who could corrupt a file.
 */
interface EntitlementStore {
    /** The cached tier, or FREE if there is none or it does not verify. */
    fun readCachedTier(): SubscriptionTier

    /** Caches [tier] with an integrity tag. */
    fun writeCachedTier(tier: SubscriptionTier)

    /** Forgets the cached tier. */
    fun clear()
}

/** The production [EntitlementStore]: HMAC-tagged, Keystore-backed. */
class KeystoreEntitlementStore(context: Context) : EntitlementStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the cached tier, or FREE if there is none, it does not verify, or
     * the Keystore key is gone.
     */
    override fun readCachedTier(): SubscriptionTier {
        val tier = prefs.getString(KEY_TIER, null) ?: return SubscriptionTier.FREE
        val tag = prefs.getString(KEY_TAG, null) ?: return SubscriptionTier.FREE
        val installId = prefs.getString(KEY_INSTALL_ID, null) ?: return SubscriptionTier.FREE

        val expected = runCatching { tagFor(tier, installId) }.getOrNull()
            ?: return SubscriptionTier.FREE

        // Constant-time compare. Not strictly required here -- a forged tier is
        // not a secret and the attacker owns the device -- but a leaky compare
        // in security-adjacent code is the kind of thing that gets copied into
        // somewhere it does matter.
        if (!constantTimeEquals(expected, tag)) return SubscriptionTier.FREE

        return SubscriptionTier.parseOrFree(tier)
    }

    /**
     * Caches [tier], signed. Never stores a record it could not sign.
     *
     * A signing failure leaves any existing record alone rather than clearing
     * it. Clearing looks safer and is not: the common cause is the Keystore key
     * being briefly unavailable, and in that case a refresh whose whole purpose
     * was to *confirm* a paid tier would instead destroy the cache and leave a
     * paying user on Free at the next offline launch. Keeping the old record
     * cannot fail open either -- reading it needs the same key, so a key that is
     * genuinely gone still yields Free (§46F).
     */
    override fun writeCachedTier(tier: SubscriptionTier) {
        val installId = existingInstallId() ?: newInstallId()
        val tag = runCatching { tagFor(tier.name, installId) }.getOrNull() ?: return
        prefs.edit()
            .putString(KEY_TIER, tier.name)
            .putString(KEY_TAG, tag)
            .putString(KEY_INSTALL_ID, installId)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TIER).remove(KEY_TAG).apply()
    }

    // ---------------------------------------------------------------------

    private fun existingInstallId(): String? = prefs.getString(KEY_INSTALL_ID, null)

    private fun newInstallId(): String {
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun tagFor(tierName: String, installId: String): String {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(hmacKey())
        val bytes = mac.doFinal("$tierName|$installId".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * The HMAC key, created on first use.
     *
     * Note there is no `setUserAuthenticationRequired` here, unlike the vault's
     * biometric key: entitlement must be readable while the vault is locked, and
     * gating it behind authentication would mean the app could not tell what
     * capacity it has until after unlock.
     */
    private fun hmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    private companion object {
        const val PREFS_NAME = "entitlement"
        const val KEY_TIER = "tier"
        const val KEY_TAG = "tag"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_ALIAS = "locknest_entitlement_hmac"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAC_ALGORITHM = "HmacSHA256"
    }
}
