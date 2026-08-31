package com.acesoftph.offlinepasswordwallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.data.model.ImportMode
import com.acesoftph.offlinepasswordwallet.data.repository.VaultState
import com.acesoftph.offlinepasswordwallet.data.repository.toUserMessage
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.importexport.CsvExporter
import com.acesoftph.offlinepasswordwallet.importexport.CsvImportPreview
import com.acesoftph.offlinepasswordwallet.importexport.CsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* --------------------------------------------------------------------------- */
/* Import CSV (§16, §18)                                                        */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCsvScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember { mutableStateOf<CsvImportPreview?>(null) }
    var mode by remember { mutableStateOf(ImportMode.ADD) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            try {
                // Read fully into memory; never copy the plaintext file into app storage.
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)
                } ?: run { message = "Could not read the selected file."; busy = false; return@launch }

                preview = CsvImporter.parse(text)
                if (preview?.entryCount == 0) message = "No data rows found in that CSV."
            } catch (e: Exception) {
                message = e.message ?: "Failed to parse CSV."
            } finally {
                busy = false
            }
        }
    }

    fun commit() {
        val p = preview ?: return
        busy = true
        scope.launch {
            val result = ServiceLocator.vaultRepository.importEntries(p.entries, mode)
            busy = false
            result.fold(
                onSuccess = {
                    doneCount = p.entryCount
                    preview = null
                    message = null
                },
                onFailure = { message = it.toUserMessage() },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import CSV") },
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
            Text(
                "Select a semicolon-delimited CSV (like the PasswordSafe template). " +
                    "The first row is treated as field names; extra columns become custom fields. " +
                    "The file is read into memory and immediately re-encrypted into the vault; " +
                    "no plaintext copy is kept.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth().testTag("choose_csv"),
            ) { Text("Choose CSV file…") }

            doneCount?.let {
                Text("Imported $it entries.", color = MaterialTheme.colorScheme.primary)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            preview?.let { p ->
                Text(
                    "Found ${p.entryCount} entries and ${p.fieldCount} fields. Import?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("import_preview"),
                )
                Text("Fields: " + p.fieldNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == ImportMode.ADD,
                        onClick = { mode = ImportMode.ADD },
                        label = { Text("Add to vault") },
                        modifier = Modifier.testTag("mode_add"),
                    )
                    FilterChip(
                        selected = mode == ImportMode.REPLACE,
                        onClick = { mode = ImportMode.REPLACE },
                        label = { Text("Replace entire vault") },
                        modifier = Modifier.testTag("mode_replace"),
                    )
                }

                Button(
                    enabled = !busy,
                    onClick = { if (mode == ImportMode.REPLACE) confirmReplace = true else commit() },
                    modifier = Modifier.fillMaxWidth().testTag("confirm_import"),
                ) { Text(if (mode == ImportMode.REPLACE) "Replace vault with import" else "Add ${p.entryCount} entries") }
            }
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace the entire vault?") },
            text = {
                Text(
                    "Every existing entry will be permanently deleted and replaced with the " +
                        "${preview?.entryCount ?: 0} imported entries. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmReplace = false; commit() },
                    modifier = Modifier.testTag("confirm_replace_yes"),
                ) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}

/* --------------------------------------------------------------------------- */
/* Export CSV (§17, §18)                                                        */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportCsvScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val entries = (state as? VaultState.Unlocked)?.entries ?: emptyList()

    var acknowledged by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val creator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val csv = CsvExporter.export(entries)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(csv.toByteArray(Charsets.UTF_8))
                        it.flush()
                    } ?: error("Could not open the destination file.")
                }
                done = true
                message = "Exported ${entries.size} entries as plaintext CSV."
            } catch (e: Exception) {
                message = e.message ?: "Export failed."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export CSV") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "CSV files are NOT encrypted. Anyone who obtains the exported file can read " +
                    "your passwords. The app hands the file straight to the location you pick " +
                    "and keeps no copy; it is never uploaded anywhere.",
                color = MaterialTheme.colorScheme.error,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                    modifier = Modifier.testTag("export_ack"),
                )
                Text("I understand the exported CSV is plaintext.")
            }
            Button(
                enabled = acknowledged && entries.isNotEmpty() && !done,
                onClick = { creator.launch("offline-password-wallet-export.csv") },
                modifier = Modifier.fillMaxWidth().testTag("do_export"),
            ) { Text("Choose location & export") }

            message?.let {
                Text(
                    it,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (done) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
