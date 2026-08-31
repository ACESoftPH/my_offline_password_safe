package com.acesoftph.offlinepasswordwallet.ui

import com.acesoftph.offlinepasswordwallet.data.model.VaultEntry
import com.acesoftph.offlinepasswordwallet.data.model.VaultField
import com.acesoftph.offlinepasswordwallet.ui.screens.filterEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {

    private val entries = listOf(
        VaultEntry(fields = listOf(
            VaultField("Title", "Facebook"),
            VaultField("Category", "Social"),
            VaultField("Username", "alice"),
            VaultField("Password", "hunter2secret"),
            VaultField("Website", "https://facebook.com"),
        )),
        VaultEntry(fields = listOf(
            VaultField("Title", "Work VPN"),
            VaultField("Username", "a.smith"),
            VaultField("Server", "vpn.corp.example", custom = true),
        )),
    )

    @Test
    fun `blank query returns all`() {
        assertEquals(2, filterEntries(entries, "  ").size)
    }

    @Test
    fun `matches title, username, website, category`() {
        assertEquals(1, filterEntries(entries, "facebook").size)
        assertEquals(1, filterEntries(entries, "social").size)
        assertEquals(1, filterEntries(entries, "alice").size)
        assertEquals("Work VPN", filterEntries(entries, "a.smith").single().value("Title"))
        assertEquals(1, filterEntries(entries, "https://facebook").size)
    }

    @Test
    fun `matches custom field name and value`() {
        assertEquals(1, filterEntries(entries, "server").size)
        assertEquals(1, filterEntries(entries, "vpn.corp").size)
    }

    @Test
    fun `never matches on a password value`() {
        assertTrue(filterEntries(entries, "hunter2secret").isEmpty())
    }
}
