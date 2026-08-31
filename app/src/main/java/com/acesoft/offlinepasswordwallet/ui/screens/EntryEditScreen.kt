package com.acesoft.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoft.offlinepasswordwallet.data.model.DefaultFields
import com.acesoft.offlinepasswordwallet.data.model.VaultEntry
import com.acesoft.offlinepasswordwallet.data.model.VaultField
import com.acesoft.offlinepasswordwallet.data.repository.VaultState
import com.acesoft.offlinepasswordwallet.data.repository.toUserMessage
import com.acesoft.offlinepasswordwallet.di.ServiceLocator
import com.acesoft.offlinepasswordwallet.ui.components.PasswordField
import com.acesoft.offlinepasswordwallet.ui.components.StrengthBar
import kotlinx.coroutines.launch

/**
 * Add / edit an entry (§6, §7, §25).
 *
 * IMPORTANT (§41): field values are held in plain `remember` state, never
 * `rememberSaveable`, so decrypted content is not written into SavedStateHandle /
 * the saved-instance Bundle. The Activity sets `configChanges` so rotation does
 * not recreate this screen; after real process death the vault is locked anyway.
 */
private class EditableField(
    name: String,
    value: String,
    val custom: Boolean,
    val sensitive: Boolean,
) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    entryId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = entryId == com.acesoft.offlinepasswordwallet.ui.navigation.Dest.NEW_ENTRY_ID
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()

    val existing = (state as? VaultState.Unlocked)?.entries?.firstOrNull { it.id == entryId }

    val fields = remember {
        mutableStateListOf<EditableField>().apply {
            val source = existing?.fields ?: DefaultFields.newEntryTemplate()
            source.forEach { add(EditableField(it.name, it.value, it.custom, it.sensitive)) }
            if (isEmpty()) {
                DefaultFields.newEntryTemplate().forEach {
                    add(EditableField(it.name, it.value, it.custom, it.sensitive))
                }
            }
        }
    }

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var addFieldDialog by remember { mutableStateOf(false) }

    fun duplicateName(candidate: String, ignore: EditableField? = null): Boolean =
        fields.any { it !== ignore && it.name.trim().equals(candidate.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New entry" else "Edit entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fields.forEachIndexed { index, field ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (field.custom) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = field.name,
                                onValueChange = { new ->
                                    error = if (duplicateName(new, field)) {
                                        "A field named \"$new\" already exists in this entry."
                                    } else null
                                    field.name = new
                                },
                                label = { Text("Custom field name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("custom_name_$index"),
                            )
                            IconButton(onClick = { fields.removeAt(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete custom field")
                            }
                        }
                    }

                    if (field.sensitive) {
                        PasswordField(
                            value = field.value,
                            onValueChange = { field.value = it },
                            label = field.name.ifBlank { "Value" },
                            modifier = Modifier.testTag("field_${field.name}"),
                        )
                        StrengthBar(field.value)
                        OutlinedButton(
                            onClick = { showGenerator = true },
                            modifier = Modifier.testTag("open_generator"),
                        ) { Text("Generate password") }
                    } else {
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { field.value = it },
                            label = { Text(field.name.ifBlank { "Value" }) },
                            singleLine = !field.name.equals(DefaultFields.COMMENTS, ignoreCase = true),
                            modifier = Modifier.fillMaxWidth().testTag("field_${field.name}"),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { addFieldDialog = true },
                modifier = Modifier.fillMaxWidth().testTag("add_custom_field"),
            ) { Text("+ Add Custom Field") }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                enabled = !busy,
                onClick = {
                    val trimmedNames = fields.map { it.name.trim() }
                    if (trimmedNames.any { it.isEmpty() }) {
                        error = "Every field needs a name."
                        return@Button
                    }
                    val lower = trimmedNames.map { it.lowercase() }
                    if (lower.size != lower.toSet().size) {
                        error = "Two fields share the same name. Field names must be unique."
                        return@Button
                    }
                    busy = true
                    val built = VaultEntry(
                        id = if (isNew) java.util.UUID.randomUUID().toString() else entryId,
                        fields = fields.map {
                            VaultField(name = it.name.trim(), value = it.value)
                        },
                        createdAtEpochMillis = existing?.createdAtEpochMillis
                            ?: System.currentTimeMillis(),
                    )
                    scope.launch {
                        val result = ServiceLocator.vaultRepository.upsertEntry(built)
                        busy = false
                        result.fold(onSuccess = { onSaved() }, onFailure = { error = it.toUserMessage() })
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("save_entry"),
            ) { Text(if (isNew) "Save new entry" else "Save changes") }
        }
    }

    if (showGenerator) {
        AlertDialog(
            onDismissRequest = { showGenerator = false },
            confirmButton = {},
            title = { Text("Generate password") },
            text = {
                GeneratorPanel(onUse = { generated ->
                    fields.firstOrNull { it.sensitive }?.value = generated
                    showGenerator = false
                })
            },
        )
    }

    if (addFieldDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addFieldDialog = false },
            title = { Text("New custom field") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Field name (e.g. PIN, Account Number)") },
                    singleLine = true,
                    modifier = Modifier.testTag("new_custom_field_name"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        when {
                            duplicateName(newName) ->
                                error = "A field named \"$newName\" already exists in this entry."
                            else -> {
                                fields.add(
                                    EditableField(
                                        name = newName.trim(),
                                        value = "",
                                        custom = true,
                                        sensitive = false,
                                    ),
                                )
                                error = null
                            }
                        }
                        addFieldDialog = false
                    },
                    modifier = Modifier.testTag("confirm_add_custom_field"),
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addFieldDialog = false }) { Text("Cancel") }
            },
        )
    }
}
