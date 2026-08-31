package com.acesoftph.offlinepasswordwallet.tier

/**
 * The limits of the free edition, in one place.
 *
 * The entry cap is enforced in [com.acesoftph.offlinepasswordwallet.data.repository.VaultRepository]
 * rather than only in the UI. Disabling the "Add entry" button is the *courtesy*;
 * the repository check is the actual limit, because entries can also arrive by
 * CSV import, by duplicating an entry, and by restoring a backup, and a limit
 * that only lives in one button is not a limit at all.
 *
 * Everything here is a plain constant compiled into the app. This is a deliberate
 * product decision, not a security boundary: an offline app cannot verify
 * entitlements against anything, and anyone who can rebuild the app can change
 * the number. It exists to define the free tier for ordinary users.
 */
object FreeTier {

    /** Maximum entries a free vault may hold. */
    const val MAX_ENTRIES = 20

    /** Appended to screen titles so the edition is always visible. */
    const val TITLE_SUFFIX = " - Free"

    /** Shown when an add is refused because the vault is already full. */
    const val LIMIT_REACHED =
        "The free version holds up to $MAX_ENTRIES entries. Delete one to add another."

    fun title(base: String): String = base + TITLE_SUFFIX

    fun isFull(entryCount: Int): Boolean = entryCount >= MAX_ENTRIES

    /** How many more entries will fit. Never negative. */
    fun remaining(entryCount: Int): Int = (MAX_ENTRIES - entryCount).coerceAtLeast(0)

    /** Keeps the first [MAX_ENTRIES]; anything beyond that is discarded. */
    fun <T> cap(entries: List<T>): List<T> =
        if (entries.size <= MAX_ENTRIES) entries else entries.take(MAX_ENTRIES)

    /** How many of [count] entries the cap would discard. */
    fun discarded(count: Int): Int = (count - MAX_ENTRIES).coerceAtLeast(0)
}

/** An add was refused because the free entry cap is already reached. */
class FreeTierLimitException(
    message: String = FreeTier.LIMIT_REACHED,
) : Exception(message)
