package com.example.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offlinepasswordwallet.data.repository.VaultState
import com.example.offlinepasswordwallet.di.ServiceLocator
import com.example.offlinepasswordwallet.settings.AppSettings
import com.example.offlinepasswordwallet.ui.components.CopyableFieldRow
import com.example.offlinepasswordwallet.util.ClipboardUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entryId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())

    val entry = (state as? VaultState.Unlocked)?.entries?.firstOrNull { it.id == entryId }
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    fun copy(label: String, value: String, sensitive: Boolean) {
        ClipboardUtil.copy(context, label, value, sensitive, settings.clipboardClearSeconds)
        scope.launch {
            snackbar.showSnackbar(
                if (sensitive) {
                    "$label copied. Clipboard clears in ${settings.clipboardClearSeconds}s."
                } else {
                    "$label copied."
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.displayTitle() ?: "Entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            ServiceLocator.vaultRepository.duplicateEntry(entryId)
                            snackbar.showSnackbar("Entry duplicated.")
                        }
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate entry") }
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.testTag("delete_entry"),
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Delete entry") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onEdit, modifier = Modifier.testTag("edit_entry_fab")) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit entry")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (entry == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("This entry no longer exists.")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entry.fields.forEachIndexed { index, field ->
                CopyableFieldRow(
                    label = field.name + if (field.custom) "  (custom)" else "",
                    value = field.value,
                    sensitive = field.sensitive,
                    onCopy = { real -> copy(field.name, real, field.sensitive) },
                    modifier = Modifier.testTag("field_row_${field.name}"),
                )
                if (index < entry.fields.lastIndex) HorizontalDivider()
            }
            Text(
                "Fields are decrypted only in memory while unlocked.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete entry?") },
            text = { Text("This permanently deletes \"${entry?.displayTitle()}\" from the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            ServiceLocator.vaultRepository.deleteEntry(entryId)
                            onBack()
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
