package com.example.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offlinepasswordwallet.crypto.SecurityAnswers
import com.example.offlinepasswordwallet.data.repository.toUserMessage
import com.example.offlinepasswordwallet.di.ServiceLocator
import com.example.offlinepasswordwallet.password.PasswordStrength
import com.example.offlinepasswordwallet.security.BiometricAuthenticator
import com.example.offlinepasswordwallet.security.BiometricKeyInvalidatedException
import com.example.offlinepasswordwallet.security.RecoveryResult
import com.example.offlinepasswordwallet.settings.AppSettings
import com.example.offlinepasswordwallet.ui.components.PasswordField
import com.example.offlinepasswordwallet.ui.components.StrengthBar
import kotlinx.coroutines.launch

/* --------------------------------------------------------------------------- */
/* First-run setup (§2, §3)                                                     */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onRestoreBackup: () -> Unit) {
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf(*Array(SecurityAnswers.REQUIRED_COUNT) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val policyError = remember(password) {
        if (password.isEmpty()) null else PasswordStrength.masterPolicyError(password.toCharArray())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up your wallet") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step == 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Create a master password", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This password encrypts everything in your wallet. It is never stored. " +
                                "If you forget it, it cannot be recovered — you can only RESET it " +
                                "using your five security answers, which creates a new master password.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                PasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "Master password",
                    modifier = Modifier.testTag("master_password"),
                )
                StrengthBar(password)
                if (policyError != null) {
                    Text(
                        policyError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm master password",
                    modifier = Modifier.testTag("confirm_password"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        error = when {
                            policyError != null -> policyError
                            password != confirm -> "The two passwords do not match."
                            else -> null
                        }
                        if (error == null) step = 1
                    },
                    modifier = Modifier.fillMaxWidth().testTag("setup_next"),
                ) { Text("Next: security questions") }
                TextButton(
                    onClick = onRestoreBackup,
                    modifier = Modifier.fillMaxWidth().testTag("setup_restore_backup"),
                ) { Text("Restore from an encrypted backup instead") }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Five security questions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "These are the ONLY way to reset a forgotten master password. " +
                                "Answers are normalized (trimmed, case-insensitive) and never stored " +
                                "in plain text — only a key derived from them is kept. " +
                                "Recovery from personal facts is weaker than a random key: choose " +
                                "answers only you know.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SecurityAnswers.QUESTIONS.forEachIndexed { index, question ->
                    OutlinedTextField(
                        value = answers[index],
                        onValueChange = { answers[index] = it; error = null },
                        label = { Text("${index + 1}. $question") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("answer_$index"),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (!SecurityAnswers.allAnswered(answers.toList())) {
                            error = "Please answer all five questions."
                            return@Button
                        }
                        busy = true
                        val pw = password.toCharArray()
                        val cf = confirm.toCharArray()
                        val submitted = answers.toList()
                        scope.launch {
                            val result = ServiceLocator.masterPasswordManager
                                .createVault(pw, cf, submitted)
                            pw.fill(' '); cf.fill(' ')
                            busy = false
                            result.onFailure { error = it.toUserMessage() }
                            // On success VaultState -> Unlocked; WalletRoot navigates away.
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("create_vault_button"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("Create encrypted vault")
                }
                TextButton(onClick = { step = 0 }) { Text("Back") }
            }
        }
    }
}

/* --------------------------------------------------------------------------- */
/* Master-password unlock + biometric unlock (§40)                              */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    onForgotMasterPassword: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val settings by ServiceLocator.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val keyManager = ServiceLocator.keyManager
    val biometricPossible = settings.biometricEnabled &&
        keyManager.isBiometricConfigured() &&
        activity != null &&
        BiometricAuthenticator.isAvailable(context)

    fun runBiometricUnlock() {
        val act = activity ?: return
        val cipher = try {
            keyManager.getDecryptCipher()
        } catch (e: BiometricKeyInvalidatedException) {
            error = e.message
            scope.launch { ServiceLocator.settingsRepository.setBiometricEnabled(false) }
            keyManager.disable()
            return
        } catch (e: Exception) {
            error = e.message
            return
        }
        BiometricAuthenticator.authenticate(
            activity = act,
            title = "Unlock Offline Password Wallet",
            subtitle = "Confirm your identity",
            cipher = cipher,
            onSuccess = { authedCipher ->
                scope.launch {
                    val dek = try {
                        keyManager.unwrapDekAfterAuth(authedCipher)
                    } catch (e: BiometricKeyInvalidatedException) {
                        error = e.message
                        ServiceLocator.settingsRepository.setBiometricEnabled(false)
                        keyManager.disable()
                        return@launch
                    }
                    ServiceLocator.vaultRepository.unlockWithDek(dek)
                        .onFailure { error = it.toUserMessage() }
                }
            },
            onError = { _, message -> error = message },
            onFailed = { },
        )
    }

    LaunchedEffect(biometricPossible) {
        if (biometricPossible) runBiometricUnlock()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Offline Password Wallet") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("The vault is locked.", style = MaterialTheme.typography.titleMedium)
            PasswordField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "Master password",
                modifier = Modifier.testTag("unlock_password"),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    busy = true
                    val pw = password.toCharArray()
                    scope.launch {
                        val result = ServiceLocator.vaultRepository.unlockWithMaster(pw)
                        pw.fill(' ')
                        busy = false
                        result.onFailure { error = it.toUserMessage() }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("unlock_button"),
            ) { Text("Unlock with master password") }

            if (biometricPossible) {
                TextButton(
                    onClick = { runBiometricUnlock() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock with biometrics") }
            }

            TextButton(
                onClick = onForgotMasterPassword,
                modifier = Modifier.fillMaxWidth().testTag("forgot_master"),
            ) { Text("Forgot master password? Reset it") }

            TextButton(
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth().testTag("unlock_restore_backup"),
            ) { Text("Restore from an encrypted backup") }
        }
    }
}

/* --------------------------------------------------------------------------- */
/* Reset master password via five security answers (§3, §50)                    */
/* --------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(onDone: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recovery = ServiceLocator.recoveryManager

    var phase by remember { mutableStateOf(RecoveryPhase.ANSWERS) }
    val answers = remember { mutableStateListOf(*Array(SecurityAnswers.REQUIRED_COUNT) { "" }) }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Reset master password") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your original master password is never stored and cannot be shown. " +
                    "Answer all five questions to authorize creating a NEW master password. " +
                    "Your entries are preserved. Wrong answers are rate-limited.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (phase == RecoveryPhase.ANSWERS) {
                recovery.questions.forEachIndexed { index, question ->
                    OutlinedTextField(
                        value = answers[index],
                        onValueChange = { answers[index] = it; message = null },
                        label = { Text("${index + 1}. $question") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("recovery_answer_$index"),
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val submitted = answers.toList()
                        scope.launch {
                            when (val r = recovery.submitAnswers(submitted)) {
                                is RecoveryResult.Verified -> {
                                    message = null
                                    phase = RecoveryPhase.NEW_PASSWORD
                                }
                                is RecoveryResult.Incorrect ->
                                    message = if (r.lockedOutMillis > 0) {
                                        "Incorrect. Locked for ${r.lockedOutMillis / 1000}s before the next try."
                                    } else {
                                        "One or more answers are incorrect."
                                    }
                                is RecoveryResult.LockedOut ->
                                    message = "Too many attempts. Try again in ${r.remainingMillis / 1000}s."
                                is RecoveryResult.Unusable -> message = r.message
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("recovery_verify"),
                ) { Text("Verify answers") }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            } else {
                val policyError = remember(newPassword) {
                    if (newPassword.isEmpty()) null
                    else PasswordStrength.masterPolicyError(newPassword.toCharArray())
                }
                Text(
                    "Answers verified. Choose a new master password.",
                    style = MaterialTheme.typography.titleMedium,
                )
                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it; message = null },
                    label = "New master password",
                    modifier = Modifier.testTag("recovery_new_password"),
                )
                StrengthBar(newPassword)
                policyError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; message = null },
                    label = "Confirm new master password",
                    modifier = Modifier.testTag("recovery_confirm_password"),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (policyError != null) {
                            message = policyError
                            return@Button
                        }
                        if (newPassword != confirm) {
                            message = "The two passwords do not match."
                            return@Button
                        }
                        busy = true
                        val pw = newPassword.toCharArray()
                        scope.launch {
                            val result = recovery.applyNewMasterPassword(pw)
                            pw.fill(' ')
                            busy = false
                            result.fold(
                                onSuccess = { onDone() },
                                onFailure = { message = it.toUserMessage() },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("recovery_apply"),
                ) { Text("Set new master password") }
            }
        }
    }
}

private enum class RecoveryPhase { ANSWERS, NEW_PASSWORD }
