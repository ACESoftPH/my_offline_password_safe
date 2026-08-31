package com.acesoftph.offlinepasswordwallet.ui.screens

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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.password.PasswordGenerator
import com.acesoftph.offlinepasswordwallet.security.BiometricAuthenticator
import com.acesoftph.offlinepasswordwallet.settings.AppSettings
import com.acesoftph.offlinepasswordwallet.settings.AutoLockTimeout
import com.acesoftph.offlinepasswordwallet.ui.components.BottomBarClearance
import com.acesoftph.offlinepasswordwallet.ui.components.SectionHeader
import com.acesoftph.offlinepasswordwallet.ui.components.SettingRow
import com.acesoftph.offlinepasswordwallet.ui.components.WalletCard
import com.acesoftph.offlinepasswordwallet.tier.FreeTier
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: FragmentActivity?,
    onChangeMaster: () -> Unit,
    onChangeAnswers: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsRepo = ServiceLocator.settingsRepository
    val keyManager = ServiceLocator.keyManager
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    var message by remember { mutableStateOf<String?>(null) }
    var showTimeouts by remember { mutableStateOf(false) }

    /**
     * Turns the setting off AND destroys any key material, so the toggle can never
     * read "on" while no usable wrapped key exists. [KeyManager.beginEnable]
     * necessarily replaces the previous Keystore key, so every failure path below
     * must land here rather than silently leaving biometrics half-configured.
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
                        .onFailure { revokeBiometric(it.message ?: "Failed to enable biometric login.") }
                }
            },
            onError = { _, msg -> revokeBiometric(msg) },
            onFailed = { },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(FreeTier.title("Settings")) }) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader("Security")

            SettingRow(
                icon = Icons.Filled.Fingerprint,
                title = "Biometric login",
                subtitle = "Wraps the vault key with an Android Keystore key gated by your biometrics. " +
                    "Your master password always still works.",
                trailing = {
                    Switch(
                        checked = settings.biometricEnabled,
                        onCheckedChange = { want -> if (want) enableBiometric() else revokeBiometric(null) },
                        modifier = Modifier.testTag("biometric_switch"),
                    )
                },
            )

            SettingRow(
                icon = Icons.Filled.LockClock,
                title = "Auto-lock timeout",
                subtitle = settings.autoLockTimeout.label,
                onClick = { showTimeouts = !showTimeouts },
            )
            if (showTimeouts) {
                WalletCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        AutoLockTimeout.entries.forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = settings.autoLockTimeout == option,
                                        onClick = { scope.launch { settingsRepo.setAutoLockTimeout(option) } },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
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
                    }
                }
            }

            SettingRow(
                icon = Icons.Filled.Screenshot,
                title = "Block screenshots",
                subtitle = "Also hides the app from the recent-apps preview",
                trailing = {
                    Switch(
                        checked = settings.blockScreenshots,
                        onCheckedChange = { scope.launch { settingsRepo.setBlockScreenshots(it) } },
                    )
                },
            )

            WalletCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "Clipboard auto-clear: ${settings.clipboardClearSeconds}s",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = settings.clipboardClearSeconds.toFloat(),
                        onValueChange = {
                            scope.launch { settingsRepo.setClipboardClearSeconds(it.roundToInt()) }
                        },
                        valueRange = 5f..120f,
                    )
                }
            }

            SettingRow(
                icon = Icons.Filled.Key,
                title = "Change master password",
                onClick = onChangeMaster,
                modifier = Modifier.testTag("nav_change_master"),
            )
            SettingRow(
                icon = Icons.Filled.QuestionAnswer,
                title = "Change security question answers",
                onClick = onChangeAnswers,
                modifier = Modifier.testTag("nav_change_answers"),
            )

            SectionHeader("Data")

            SettingRow(
                icon = Icons.Filled.Backup,
                title = "Export encrypted backup",
                subtitle = "Protected by its own passphrase; restores on any device",
                onClick = onExportBackup,
                modifier = Modifier.testTag("nav_export_backup"),
            )
            SettingRow(
                icon = Icons.Filled.Restore,
                title = "Restore from encrypted backup",
                onClick = onRestoreBackup,
                modifier = Modifier.testTag("nav_restore_backup"),
            )
            SettingRow(
                icon = Icons.Filled.UploadFile,
                title = "Import CSV",
                subtitle = "Semicolon-delimited; extra columns become custom fields",
                onClick = onImport,
            )
            SettingRow(
                icon = Icons.Filled.UploadFile,
                title = "Export CSV",
                subtitle = "Plain text — anyone with the file can read your passwords",
                tint = MaterialTheme.colorScheme.error,
                onClick = onExport,
            )

            SectionHeader("Password generator defaults")

            WalletCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "Default length: ${settings.defaultPasswordLength}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = settings.defaultPasswordLength.toFloat(),
                        onValueChange = {
                            scope.launch { settingsRepo.setDefaultPasswordLength(it.roundToInt()) }
                        },
                        valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..
                            PasswordGenerator.MAX_LENGTH.toFloat(),
                    )
                }
            }
            SettingRow(
                icon = Icons.Filled.Tune,
                title = "Use special characters by default",
                trailing = {
                    Switch(
                        checked = settings.defaultUseSpecialChars,
                        onCheckedChange = { scope.launch { settingsRepo.setDefaultUseSpecialChars(it) } },
                    )
                },
            )

            Text(
                "Lock Nest — no network permission, no cloud, no analytics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = BottomBarClearance),
            )
        }
    }
}
