package com.acesoftph.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.data.model.DefaultFields
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.password.PasswordStrength
import com.acesoftph.offlinepasswordwallet.settings.AppSettings
import com.acesoftph.offlinepasswordwallet.ui.components.Chip
import com.acesoftph.offlinepasswordwallet.ui.components.CopyableFieldRow
import com.acesoftph.offlinepasswordwallet.ui.components.EntryAvatar
import com.acesoftph.offlinepasswordwallet.ui.components.SectionHeader
import com.acesoftph.offlinepasswordwallet.util.ClipboardUtil
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
            ExtendedFloatingActionButton(
                onClick = onEdit,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("edit_entry_fab"),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Text("  Edit", fontWeight = FontWeight.SemiBold)
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

        val standard = entry.fields.filter { !it.custom }
        val custom = entry.fields.filter { it.custom }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- header ------------------------------------------------------
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EntryAvatar(entry.displayTitle(), size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.displayTitle(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        entry.value(DefaultFields.CATEGORY)?.takeIf { it.isNotBlank() }?.let {
                            Chip(
                                it,
                                container = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                                content = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            // ---- fields ------------------------------------------------------
            standard.forEach { field ->
                CopyableFieldRow(
                    label = field.name,
                    value = field.value,
                    sensitive = field.sensitive,
                    onCopy = { real -> copy(field.name, real, field.sensitive) },
                    badge = if (field.sensitive && field.value.isNotEmpty()) {
                        PasswordStrength.evaluate(field.value).level.label
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("field_row_${field.name}"),
                )
            }

            if (custom.isNotEmpty()) {
                SectionHeader("Custom fields")
                custom.forEach { field ->
                    CopyableFieldRow(
                        label = field.name,
                        value = field.value,
                        sensitive = field.sensitive,
                        onCopy = { real -> copy(field.name, real, field.sensitive) },
                        modifier = Modifier.testTag("field_row_${field.name}"),
                    )
                }
            }

            Text(
                "Fields are decrypted only in memory while the vault is unlocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 96.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete entry?") },
            text = { Text("This permanently deletes “${entry?.displayTitle()}” from the vault.") },
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
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
