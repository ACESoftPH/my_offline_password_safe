package com.acesoftph.offlinepasswordwallet.util

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression tests for the review finding that a following NON-sensitive copy
 * cancelled the pending clipboard wipe and silently dropped the "clipboard clears
 * in Ns" guarantee the UI had just shown the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardUtilTest {

    private lateinit var ctx: Context
    private lateinit var cm: ClipboardManager

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // ClipboardUtil is an object; drain any task left by a previous test method
        // so each case starts from a known state.
        ClipboardUtil.copy(ctx, "reset", "reset", sensitive = false, clearAfterSeconds = 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
    }

    private fun current(): String? =
        runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()

    private fun advance(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    @Test
    fun `a sensitive copy is cleared after the configured delay`() {
        ClipboardUtil.copy(ctx, "Password", "s3cret", sensitive = true, clearAfterSeconds = 30)
        assertEquals("s3cret", current())
        advance(31)
        assertNotEquals("s3cret", current())
    }

    @Test
    fun `the clear promise survives a following non-sensitive copy`() {
        ClipboardUtil.copy(ctx, "Password", "s3cret", sensitive = true, clearAfterSeconds = 30)
        advance(5)
        // Copying the username used to cancel the pending wipe outright.
        ClipboardUtil.copy(ctx, "Username", "alice@example.com", sensitive = false, clearAfterSeconds = 30)
        assertEquals("alice@example.com", current())

        advance(31)
        assertNotEquals("alice@example.com", current())
    }

    @Test
    fun `a non-sensitive copy on its own is not scheduled for clearing`() {
        ClipboardUtil.copy(ctx, "Website", "https://example.com", sensitive = false, clearAfterSeconds = 30)
        advance(60)
        assertEquals("https://example.com", current())
    }

    @Test
    fun `a newer sensitive copy resets the window instead of being wiped early`() {
        ClipboardUtil.copy(ctx, "Password", "first", sensitive = true, clearAfterSeconds = 30)
        advance(25)
        ClipboardUtil.copy(ctx, "Password", "second", sensitive = true, clearAfterSeconds = 30)
        advance(10) // 35s after the FIRST copy, only 10s after the second
        assertEquals("second", current())
        advance(25)
        assertNotEquals("second", current())
    }

    @Test
    fun `clearing does not wipe a value the user copied from elsewhere`() {
        ClipboardUtil.copy(ctx, "Password", "ours", sensitive = true, clearAfterSeconds = 30)
        advance(5)
        // Something outside the app replaces the clipboard.
        cm.setPrimaryClip(android.content.ClipData.newPlainText("other", "someone else's text"))
        advance(40)
        assertEquals("someone else's text", current())
    }
}
