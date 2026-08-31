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
    private var pendingClear: Runnable? = null

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

        pendingClear?.let { mainHandler.removeCallbacks(it) }
        pendingClear = null

        if (sensitive && clearAfterSeconds > 0) {
            val expected = value
            val task = Runnable { clearIfUnchanged(cm, expected) }
            pendingClear = task
            mainHandler.postDelayed(task, clearAfterSeconds * 1000L)
        }
    }

    private fun clearIfUnchanged(cm: ClipboardManager, expected: String) {
        val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (current != null && current != expected) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", " "))
            }
        }
        pendingClear = null
    }
}
