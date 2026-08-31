package com.acesoft.offlinepasswordwallet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoft.offlinepasswordwallet.crypto.SecurityAnswers
import com.acesoft.offlinepasswordwallet.data.backup.BackupCodec
import com.acesoft.offlinepasswordwallet.data.backup.BackupPreview
import com.acesoft.offlinepasswordwallet.data.model.ImportMode
import com.acesoft.offlinepasswordwallet.data.model.VaultDocument
import com.acesoft.offlinepasswordwallet.data.repository.VaultState
import com.acesoft.offlinepasswordwallet.data.repository.toUserMessage
import com.acesoft.offlinepasswordwallet.di.ServiceLocator
import com.acesoft.offlinepasswordwallet.password.PasswordStrength
import com.acesoft.offlinepasswordwallet.ui.components.PasswordField
import com.acesoft.offlinepasswordwallet.ui.components.StrengthBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateStamp(millis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

/* --------------------------------------------------------------------------- */
/* Export encrypted backup                                                      */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val unlocked = state is VaultState.Unlocked

    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val policyError = remember(passphrase) {
        if (passphrase.isEmpty()) null else PasswordStrength.masterPolicyError(passphrase.toCharArray())
    }

    val creator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pass = passphrase.toCharArray()
        scope.launch {
            val result = ServiceLocator.backupManager.exportBytes(pass)
            pass.fill(' ')
            result.fold(
                onSuccess = { bytes ->
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(bytes); it.flush()
                            } ?: error("Could not open the destination file.")
                        }
                        done = true
                        message = "Encrypted backup saved."
                    } catch (e: Exception) {
                        message = e.message ?: "Could not write the backup file."
                    }
                },
                onFailure = { message = it.toUserMessage() },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export encrypted backup") },
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
            if (!unlocked) {
                Text("Unlock the vault first to export a backup.")
                return@Column
            }
            Text(
                "This creates an ENCRYPTED file containing every entry. It is protected by a " +
                    "separate backup passphrase (not your master password) and can be restored on " +
                    "this phone or a new one. If you lose the passphrase, the backup cannot be " +
                    "opened. The file is written only to the location you choose and is never " +
                    "uploaded anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
            PasswordField(
                value = passphrase,
                onValueChange = { passphrase = it; message = null },
                label = "Backup passphrase",
                modifier = Modifier.testTag("backup_passphrase"),
            )
            StrengthBar(passphrase)
            policyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            PasswordField(
                value = confirm,
                onValueChange = { confirm = it; message = null },
                label = "Confirm backup passphrase",
                modifier = Modifier.testTag("backup_passphrase_confirm"),
            )
            message?.let {
                Text(
                    it,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = !done && passphrase.isNotEmpty(),
                onClick = {
                    when {
                        policyError != null -> message = policyError
                        passphrase != confirm -> message = "The two passphrases do not match."
                        else -> creator.launch(
                            "offline-password-wallet-${dateStamp()}.${BackupCodec.FILE_EXTENSION}",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("do_export_backup"),
            ) { Text("Choose location & export encrypted backup") }

            if (done) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/* --------------------------------------------------------------------------- */
/* Restore from encrypted backup                                                */
/* --------------------------------------------------------------------------- */

private enum class RestorePhase { PICK, CHOOSE_MODE, NEW_VAULT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by ServiceLocator.vaultRepository.state.collectAsStateWithLifecycle()
    val vaultExists = ServiceLocator.vaultRepository.isInitialized()
    val vaultUnlocked = state is VaultState.Unlocked

    var phase by remember { mutableStateOf(RestorePhase.PICK) }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var decrypted by remember { mutableStateOf<VaultDocument?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }

    // new-vault sub-form
    var newMaster by remember { mutableStateOf("") }
    var newMasterConfirm by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf(*Array(SecurityAnswers.REQUIRED_COUNT) { "" }) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            try {
                fileBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (fileBytes == null) message = "Could not read the selected file."
                else message = "File selected. Enter the backup passphrase."
            } catch (e: Exception) {
                message = e.message ?: "Could not read the selected file."
            } finally {
                busy = false
            }
        }
    }

    fun openBackup() {
        val bytes = fileBytes ?: run { message = "Choose a backup file first."; return }
        busy = true
        val pass = passphrase.toCharArray()
        scope.launch {
            val result = ServiceLocator.backupManager.previewAndDecrypt(bytes, pass)
            pass.fill(' ')
            busy = false
            result.fold(
                onSuccess = { (p, doc) ->
                    preview = p
                    decrypted = doc
                    message = null
                    phase = if (vaultUnlocked) RestorePhase.CHOOSE_MODE else RestorePhase.NEW_VAULT
                },
                onFailure = { message = it.toUserMessage() },
            )
        }
    }

    fun restoreAsNewVault() {
        val doc = decrypted ?: return
        val policy = PasswordStrength.masterPolicyError(newMaster.toCharArray())
        when {
            policy != null -> { message = policy; return }
            newMaster != newMasterConfirm -> { message = "The two passwords do not match."; return }
            !SecurityAnswers.allAnswered(answers.toList()) -> { message = "Answer all five questions."; return }
        }
        busy = true
        val master = newMaster.toCharArray()
        val submitted = answers.toList()
        scope.launch {
            val result = ServiceLocator.backupManager.restoreAsNewVault(doc, master, submitted)
            master.fill(' ')
            result.fold(
                onSuccess = {
                    // The DEK is brand new, so any device-bound biometric wrapping is stale.
                    ServiceLocator.keyManager.disable()
                    ServiceLocator.settingsRepository.setBiometricEnabled(false)
                    busy = false
                    onDone()
                },
                onFailure = { busy = false; message = it.toUserMessage() },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from encrypted backup") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            when (phase) {
                RestorePhase.PICK -> {
                    Text(
                        "Select an Offline Password Wallet encrypted backup (.${BackupCodec.FILE_EXTENSION}) " +
                            "and enter its backup passphrase. The file is read into memory only.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            picker.launch(
                                arrayOf("application/octet-stream", "application/json", "*/*"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("choose_backup_file"),
                    ) { Text(if (fileBytes == null) "Choose backup file…" else "Backup file selected — choose another") }

                    PasswordField(
                        value = passphrase,
                        onValueChange = { passphrase = it; message = null },
                        label = "Backup passphrase",
                        modifier = Modifier.testTag("restore_passphrase"),
                    )
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !busy && fileBytes != null && passphrase.isNotEmpty(),
                        onClick = { openBackup() },
                        modifier = Modifier.fillMaxWidth().testTag("open_backup"),
                    ) { Text("Open backup") }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }

                RestorePhase.CHOOSE_MODE -> {
                    PreviewSummary(preview)
                    Text("The vault is unlocked. How should this backup be applied?")
                    Button(
                        enabled = !busy,
                        onClick = {
                            val doc = decrypted ?: return@Button
                            busy = true
                            scope.launch {
                                val r = ServiceLocator.backupManager
                                    .mergeIntoUnlockedVault(doc, ImportMode.ADD)
                                busy = false
                                r.fold(onSuccess = { onDone() }, onFailure = { message = it.toUserMessage() })
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("restore_merge_add"),
                    ) { Text("Add ${preview?.entryCount ?: 0} entries to the current vault") }

                    OutlinedButton(
                        onClick = { phase = RestorePhase.NEW_VAULT },
                        modifier = Modifier.fillMaxWidth().testTag("restore_replace_choice"),
                    ) { Text("Replace everything and set a new master password") }

                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }

                RestorePhase.NEW_VAULT -> {
                    PreviewSummary(preview)
                    Text(
                        if (vaultExists) {
                            "This will REPLACE the vault on this device. Choose a new master password " +
                                "and new security answers for the restored vault."
                        } else {
                            "Choose a master password and security answers for the restored vault."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val policyError = remember(newMaster) {
                        if (newMaster.isEmpty()) null
                        else PasswordStrength.masterPolicyError(newMaster.toCharArray())
                    }
                    PasswordField(
                        value = newMaster,
                        onValueChange = { newMaster = it; message = null },
                        label = "New master password",
                        modifier = Modifier.testTag("restore_new_master"),
                    )
                    StrengthBar(newMaster)
                    policyError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    PasswordField(
                        value = newMasterConfirm,
                        onValueChange = { newMasterConfirm = it; message = null },
                        label = "Confirm new master password",
                        modifier = Modifier.testTag("restore_new_master_confirm"),
                    )
                    SecurityAnswers.QUESTIONS.forEachIndexed { index, q ->
                        OutlinedTextField(
                            value = answers[index],
                            onValueChange = { answers[index] = it; message = null },
                            label = { Text("${index + 1}. $q") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("restore_answer_$index"),
                        )
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !busy,
                        onClick = { if (vaultExists) confirmOverwrite = true else restoreAsNewVault() },
                        modifier = Modifier.fillMaxWidth().testTag("restore_apply"),
                    ) { Text(if (vaultExists) "Replace vault with backup" else "Restore vault") }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Replace the vault on this device?") },
            text = {
                Text(
                    "Every entry currently stored on this device will be permanently deleted and " +
                        "replaced with the ${preview?.entryCount ?: 0} entries from the backup. " +
                        "Biometric login will be turned off. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmOverwrite = false; restoreAsNewVault() },
                    modifier = Modifier.testTag("confirm_restore_overwrite"),
                ) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PreviewSummary(preview: BackupPreview?) {
    if (preview == null) return
    Text(
        "Backup opened: ${preview.entryCount} entries · created ${dateStamp(preview.createdAtEpochMillis)} " +
            "· app ${preview.appVersionName}",
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().testTag("restore_preview"),
    )
}
