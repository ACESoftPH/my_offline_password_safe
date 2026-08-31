package com.example.offlinepasswordwallet.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Clipboard helper (§9). All copy operations in the app go through here.
 *
 * Behaviour by Android version:
 *  - API 33+ : the clip is flagged `EXTRA_IS_SENSITIVE`, so the OS does NOT show
 *    the copied value in the clipboard-preview toast/UI.
 *  - API 28+ : after [clearAfterSeconds] we call `clearPrimaryClip()` if our
 *    value is still the current clip.
 *  - API 26-27 : `clearPrimaryClip` is unavailable, so we overwrite the clip with
 *    a single space instead.
 *
 * Documented limitations: another app or a clipboard-manager may already have
 * read the value before it is cleared; the app cannot control that. Sensitive
 * values are never logged.
 */
object ClipboardUtil {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingClear: Runnable? = null

    /**
     * True while a timed clear is outstanding. Once the app has promised the user
     * "the clipboard will be cleared", a following *non-sensitive* copy must not
     * silently drop that promise — whatever we put on the clipboard next is still
     * cleared on schedule.
     */
    @Volatile
    private var clearPromised: Boolean = false

    fun copy(
        context: Context,
        label: String,
        value: String,
        sensitive: Boolean,
        clearAfterSeconds: Int,
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        } else if (sensitive) {
            @Suppress("DEPRECATION")
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)

        val hadPromise = clearPromised
        pendingClear?.let { mainHandler.removeCallbacks(it) }
        pendingClear = null

        // Schedule a clear when this value is sensitive, and also when a clear was
        // already promised for the value we just overwrote — otherwise copying a
        // username right after a password would cancel the pending wipe and leave
        // the clipboard populated indefinitely.
        if ((sensitive || hadPromise) && clearAfterSeconds > 0) {
            val expected = value
            lateinit var task: Runnable
            task = Runnable { clearIfUnchanged(cm, expected, task) }
            pendingClear = task
            clearPromised = true
            mainHandler.postDelayed(task, clearAfterSeconds * 1000L)
        } else {
            clearPromised = false
        }
    }

    private fun clearIfUnchanged(cm: ClipboardManager, expected: String, self: Runnable) {
        // Only retire the promise if THIS task is still the current one; a newer
        // copy may have replaced it between posting and running.
        if (pendingClear !== self) return
        val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (current == null || current == expected) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", " "))
                }
            }
        }
        pendingClear = null
        clearPromised = false
    }
}
