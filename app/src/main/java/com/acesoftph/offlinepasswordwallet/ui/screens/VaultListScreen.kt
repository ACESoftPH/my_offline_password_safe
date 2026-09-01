package com.acesoftph.offlinepasswordwallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.data.model.VaultEntry
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.ui.components.BottomBarClearance
import com.acesoftph.offlinepasswordwallet.ui.components.EntryAvatar
import com.acesoftph.offlinepasswordwallet.ui.components.SectionHeader
import com.acesoftph.offlinepasswordwallet.ui.components.WalletCard
import com.acesoftph.offlinepasswordwallet.ui.components.tierTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onOpenEntry: (String) -> Unit,
    onAddEntry: () -> Unit,
    onLockAndExit: () -> Unit = {},
    onUpgrade: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val entries = (state as? VaultState.Unlocked)?.entries ?: emptyList()

    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) { filterEntries(entries, query) }
    val grouped = remember(filtered) { groupByInitial(filtered) }

    // Back on the vault root must not fall through to the auth destination
    // sitting beneath it — that would show "The vault is locked" while it is
    // still open. Instead, the first press arms an exit and the second one takes
    // it, locking the vault on the way out so reopening needs the master password
    // or biometrics again. The snackbar's own lifetime is the arming window.
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler {
        if (exitArmed) {
            onLockAndExit()
        } else {
            exitArmed = true
            scope.launch {
                snackbar.showSnackbar("Press back again to lock and exit")
                exitArmed = false
            }
        }
    }

    // Every capacity question goes to the entitlement layer; this screen holds
    // no notion of which tier is in play or what it allows (§46A.2).
    val entitlement = ServiceLocator.entitlementManager
    val tier by entitlement.tier.collectAsStateWithLifecycle()
    val maxEntries = remember(tier) { entitlement.getMaximumEntries() }
    val unlimited = remember(tier) { entitlement.isUnlimited() }
    val vaultFull = !entitlement.canCreateEntry(entries.size)
    val overCapacity = entitlement.isOverCapacity(entries.size)
    var showCapacityPrompt by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tierTitle("Password List")) },
                actions = {
                    IconButton(
                        onClick = { scope.launch { ServiceLocator.vaultRepository.lock() } },
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock now")
                    }
                },
            )
        },
        floatingActionButton = {
            // At the free cap the button stops adding, takes on the disabled
            // palette, and reports itself disabled to accessibility. Tapping it
            // still explains why rather than doing nothing: a control that is
            // visibly dead and silent reads as a bug.
            ExtendedFloatingActionButton(
                onClick = { if (vaultFull) showCapacityPrompt = true else onAddEntry() },
                containerColor = if (vaultFull) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (vaultFull) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                // ExtendedFloatingActionButton has no `enabled` parameter, and its
                // internal clickable publishes an enabled node that a plain
                // `semantics { disabled() }` does not displace -- verified on
                // device, where the button still reported enabled=true while
                // refusing every tap. clearAndSetSemantics replaces that subtree
                // outright, so the control finally announces itself as disabled
                // instead of lying to accessibility services.
                modifier = Modifier
                    .then(
                        if (vaultFull) {
                            Modifier.clearAndSetSemantics {
                                contentDescription = "Add entry"
                                disabled()
                            }
                        } else {
                            Modifier
                        },
                    )
                    .testTag("add_entry_fab"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add entry", fontWeight = FontWeight.SemiBold)
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("search_field"),
            )

            if (entries.isNotEmpty()) {
                Text(
                    // Showing the cap alongside the count makes the limit
                    // discoverable before the button goes dead, not after.
                    // An unlimited vault must not advertise a number: showing
                    // "of 2,147,483,647" would be both absurd and a leak of the
                    // sentinel used for "no practical limit".
                    text = when {
                        query.isNotBlank() -> "${filtered.size} of ${entries.size} shown"
                        unlimited -> "${"%,d".format(entries.size)} entries"
                        overCapacity ->
                            "${"%,d".format(entries.size)} entries · over your " +
                                "${"%,d".format(maxEntries)}-entry limit"
                        else ->
                            "${"%,d".format(entries.size)} of ${"%,d".format(maxEntries)} entries" +
                                if (vaultFull) " · limit reached" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vaultFull && !unlimited && query.isBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (entries.isEmpty()) {
                            "No entries yet.\nTap “Add entry” to create one."
                        } else {
                            "Nothing matches “$query”."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("entry_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = BottomBarClearance),
                ) {
                    grouped.forEach { (initial, group) ->
                        item(key = "hdr_$initial") { SectionHeader(initial) }
                        items(group, key = { it.id }) { entry ->
                            EntryRow(entry = entry, onClick = { onOpenEntry(entry.id) })
                        }
                    }
                }
            }
        }
    }
    // §46G: refusing an add has to come with an explanation and a way forward,
    // not a dead button. Existing entries are untouched and fully usable while
    // this is showing -- the limit blocks creation only (§46H).
    if (showCapacityPrompt) {
        val next = entitlement.nextTierUp()
        AlertDialog(
            onDismissRequest = { showCapacityPrompt = false },
            title = { Text("Vault capacity reached") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entitlement.capacityMessage())
                    if (next != null) {
                        Text(
                            "${next.priceLabel} · one-time purchase, no subscription.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Your existing entries are unaffected. You can still open, " +
                            "edit and delete them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (next != null) {
                    TextButton(
                        onClick = { showCapacityPrompt = false; onUpgrade() },
                        modifier = Modifier.testTag("capacity_upgrade"),
                    ) { Text("Upgrade") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCapacityPrompt = false }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun EntryRow(entry: VaultEntry, onClick: () -> Unit) {
    val title = entry.displayTitle()
    WalletCard(
        onClick = onClick,
        modifier = Modifier.testTag("entry_row_$title"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryAvatar(title)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.displaySubtitle()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Groups entries under their first character for the sectioned list. Purely
 * presentational — nothing about the stored data changes. Anything not starting
 * with a letter collects under "#".
 */
internal fun groupByInitial(entries: List<VaultEntry>): List<Pair<String, List<VaultEntry>>> =
    entries
        .sortedBy { it.displayTitle().lowercase() }
        .groupBy { entry ->
            val c = entry.displayTitle().trim().firstOrNull()
            if (c != null && c.isLetter()) c.uppercase() else "#"
        }
        .toList()
        .sortedBy { (key, _) -> if (key == "#") "zzz" else key }

/**
 * Filters on NON-SENSITIVE data only: every field name, plus the values of
 * fields that are not marked [com.acesoftph.offlinepasswordwallet.data.model.VaultField.sensitive]
 * (so password values are never matched). All in memory; no index is persisted.
 */
internal fun filterEntries(entries: List<VaultEntry>, query: String): List<VaultEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    return entries.filter { entry ->
        entry.fields.any { field ->
            field.name.lowercase().contains(q) ||
                (!field.sensitive && field.value.lowercase().contains(q))
        }
    }
}
