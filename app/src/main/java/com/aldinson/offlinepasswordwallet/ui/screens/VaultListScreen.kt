package com.aldinson.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldinson.offlinepasswordwallet.data.model.VaultEntry
import com.aldinson.offlinepasswordwallet.data.repository.VaultState
import com.aldinson.offlinepasswordwallet.di.ServiceLocator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onOpenEntry: (String) -> Unit,
    onAddEntry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGenerator: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val entries = (state as? VaultState.Unlocked)?.entries ?: emptyList()

    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val filtered = remember(entries, query) { filterEntries(entries, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                actions = {
                    IconButton(onClick = {
                        scope.launch { ServiceLocator.vaultRepository.lock() }
                    }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock now")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Password generator") },
                            onClick = { menuOpen = false; onOpenGenerator() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import / Export → Import CSV") },
                            onClick = { menuOpen = false; onImport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import / Export → Export CSV") },
                            onClick = { menuOpen = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import / Export → Export encrypted backup") },
                            onClick = { menuOpen = false; onExportBackup() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import / Export → Restore from encrypted backup") },
                            onClick = { menuOpen = false; onRestoreBackup() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEntry,
                modifier = Modifier.testTag("add_entry_fab"),
            ) { Icon(Icons.Filled.Add, contentDescription = "Add entry") }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search title, category, username, website, custom fields") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("search_field"),
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (entries.isEmpty()) "No entries yet. Tap + to add one." else "No matches.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("entry_list"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    entry.displayTitle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = entry.displaySubtitle()?.let { sub ->
                                { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry.id) }
                                .testTag("entry_row_${entry.displayTitle()}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Filters on NON-SENSITIVE data only: every field name, plus the values of
 * fields that are not marked [com.aldinson.offlinepasswordwallet.data.model.VaultField.sensitive]
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
