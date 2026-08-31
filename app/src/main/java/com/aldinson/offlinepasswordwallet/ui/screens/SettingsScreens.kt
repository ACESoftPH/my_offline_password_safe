package com.aldinson.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aldinson.offlinepasswordwallet.crypto.SecurityAnswers
import com.aldinson.offlinepasswordwallet.data.repository.toUserMessage
import com.aldinson.offlinepasswordwallet.di.ServiceLocator
import com.aldinson.offlinepasswordwallet.password.PasswordGenerator
import com.aldinson.offlinepasswordwallet.password.PasswordStrength
import com.aldinson.offlinepasswordwallet.security.BiometricAuthenticator
import com.aldinson.offlinepasswordwallet.settings.AppSettings
import com.aldinson.offlinepasswordwallet.settings.AutoLockTimeout
import com.aldinson.offlinepasswordwallet.ui.components.PasswordField
import com.aldinson.offlinepasswordwallet.ui.components.StrengthBar
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: FragmentActivity?,
    onBack: () -> Unit,
    onChangeMaster: () -> Unit,
    onChangeAnswers: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsRepo = ServiceLocator.settingsRepository
    val keyManager = ServiceLocator.keyManager
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    var message by remember { mutableStateOf<String?>(null) }

    /**
     * Turns the setting off AND destroys any key material, so the toggle can never
     * read "on" while no usable wrapped key exists. [beginEnable] necessarily
     * replaces the previous Keystore key, so every failure path below must land
     * here rather than silently leaving biometrics half-configured.
     */
    fun revokeBiometric(reason: String?) {
        keyManager.disable()
        scope.launch {
            settingsRepo.setBiometricEnabled(false)
            if (reason != null) message = reason
        }
    }

    fun enableBiometric() {
        val dek = ServiceLocator.vaultRepository.currentDek()
        if (dek == null) { message = "Unlock the vault first."; return }
        if (activity == null || !BiometricAuthenticator.isAvailable(context)) {
            message = "Biometric authentication is not available on this device."
            return
        }
        val cipher = try {
            keyManager.beginEnable()
        } catch (e: Exception) {
            revokeBiometric(e.message ?: "Could not prepare the biometric key.")
            return
        }
        BiometricAuthenticator.authenticate(
            activity = activity,
            title = "Enable biometric login",
            subtitle = "Confirm to protect your vault key with biometrics",
            cipher = cipher,
            onSuccess = { authed ->
                scope.launch {
                    runCatching { keyManager.finishEnable(authed, dek) }
                        .onSuccess {
                            settingsRepo.setBiometricEnabled(true)
                            message = "Biometric login enabled."
                        }
                        .onFailure {
                            revokeBiometric(it.message ?: "Failed to enable biometric login.")
                        }
                }
            },
            onError = { _, msg -> revokeBiometric(msg) },
            onFailed = { },
        )
    }

    fun disableBiometric() {
        keyManager.disable()
        scope.launch {
            settingsRepo.setBiometricEnabled(false)
            message = "Biometric login disabled."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            SectionHeader("Security")

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric login")
                    Text(
                        "Wraps the vault key with an Android Keystore key gated by your biometrics. " +
                            "Master password always still works.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.biometricEnabled,
                    onCheckedChange = { want -> if (want) enableBiometric() else disableBiometric() },
                    modifier = Modifier.testTag("biometric_switch"),
                )
            }

            Text("Auto-lock timeout", modifier = Modifier.padding(top = 8.dp))
            AutoLockTimeout.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.autoLockTimeout == option,
                            onClick = { scope.launch { settingsRepo.setAutoLockTimeout(option) } },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.autoLockTimeout == option,
                        onClick = { scope.launch { settingsRepo.setAutoLockTimeout(option) } },
                        modifier = Modifier.testTag("autolock_${option.name}"),
                    )
                    Text(option.label)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Block screenshots / screen recording")
                Switch(
                    checked = settings.blockScreenshots,
                    onCheckedChange = { scope.launch { settingsRepo.setBlockScreenshots(it) } },
                )
            }

            Text("Clipboard auto-clear: ${settings.clipboardClearSeconds}s")
            Slider(
                value = settings.clipboardClearSeconds.toFloat(),
                onValueChange = { scope.launch { settingsRepo.setClipboardClearSeconds(it.roundToInt()) } },
                valueRange = 5f..120f,
            )

            TextButton(onClick = onChangeMaster, modifier = Modifier.testTag("nav_change_master")) {
                Text("Change master password")
            }
            TextButton(onClick = onChangeAnswers, modifier = Modifier.testTag("nav_change_answers")) {
                Text("Change security question answers")
            }

            HorizontalDivider()
            SectionHeader("Data")
            TextButton(onClick = onImport) { Text("Import CSV") }
            TextButton(onClick = onExport) { Text("Export CSV (plaintext)") }
            TextButton(
                onClick = onExportBackup,
                modifier = Modifier.testTag("nav_export_backup"),
            ) { Text("Export encrypted backup") }
            TextButton(
                onClick = onRestoreBackup,
                modifier = Modifier.testTag("nav_restore_backup"),
            ) { Text("Restore from encrypted backup") }
            Text(
                "An encrypted backup is protected by its own passphrase and can be restored on " +
                    "this or another device. Keep it and its passphrase somewhere safe.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            SectionHeader("Password generator defaults")
            Text("Default length: ${settings.defaultPasswordLength}")
            Slider(
                value = settings.defaultPasswordLength.toFloat(),
                onValueChange = { scope.launch { settingsRepo.setDefaultPasswordLength(it.roundToInt()) } },
                valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Use special characters by default")
                Switch(
                    checked = settings.defaultUseSpecialChars,
                    onCheckedChange = { scope.launch { settingsRepo.setDefaultUseSpecialChars(it) } },
                )
            }

            HorizontalDivider()
            Text(
                "Offline Password Wallet — no network permission, no cloud, no analytics.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/* --------------------------------------------------------------------------- */
/* Change master password (§28)                                                 */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeMasterPasswordScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    val policyError = remember(next) {
        if (next.isEmpty()) null else PasswordStrength.masterPolicyError(next.toCharArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change master password") },
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
            PasswordField(current, { current = it; message = null }, "Current master password",
                modifier = Modifier.testTag("cmp_current"))
            PasswordField(next, { next = it; message = null }, "New master password",
                modifier = Modifier.testTag("cmp_new"))
            StrengthBar(next)
            policyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            PasswordField(confirm, { confirm = it; message = null }, "Confirm new master password",
                modifier = Modifier.testTag("cmp_confirm"))
            message?.let {
                Text(
                    it,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = !busy && !done,
                onClick = {
                    busy = true
                    val c = current.toCharArray(); val n = next.toCharArray(); val cf = confirm.toCharArray()
                    scope.launch {
                        val result = ServiceLocator.masterPasswordManager
                            .changeMasterPassword(c, n, cf)
                        c.fill(' '); n.fill(' '); cf.fill(' ')
                        busy = false
                        result.fold(
                            onSuccess = {
                                done = true
                                message = "Master password changed. The old one no longer works."
                            },
                            onFailure = { message = it.toUserMessage() },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("cmp_submit"),
            ) { Text("Change master password") }
            if (done) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/* --------------------------------------------------------------------------- */
/* Change security answers (§29)                                                */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeSecurityAnswersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var master by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf(*Array(SecurityAnswers.REQUIRED_COUNT) { "" }) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change security answers") },
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
                "The five questions are fixed. Enter your current master password to authorize " +
                    "replacing the recovery answers.",
                style = MaterialTheme.typography.bodySmall,
            )
            PasswordField(master, { master = it; message = null }, "Current master password",
                modifier = Modifier.testTag("csa_master"))
            SecurityAnswers.QUESTIONS.forEachIndexed { index, q ->
                OutlinedTextField(
                    value = answers[index],
                    onValueChange = { answers[index] = it; message = null },
                    label = { Text("${index + 1}. $q") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("csa_answer_$index"),
                )
            }
            message?.let {
                Text(
                    it,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                enabled = !busy && !done,
                onClick = {
                    if (!SecurityAnswers.allAnswered(answers.toList())) {
                        message = "Answer all five questions."
                        return@Button
                    }
                    busy = true
                    val m = master.toCharArray()
                    val submitted = answers.toList()
                    scope.launch {
                        val result = ServiceLocator.masterPasswordManager
                            .changeSecurityAnswers(m, submitted)
                        m.fill(' ')
                        busy = false
                        result.fold(
                            onSuccess = { done = true; message = "Security answers updated." },
                            onFailure = { message = it.toUserMessage() },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("csa_submit"),
            ) { Text("Update security answers") }
            if (done) {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
