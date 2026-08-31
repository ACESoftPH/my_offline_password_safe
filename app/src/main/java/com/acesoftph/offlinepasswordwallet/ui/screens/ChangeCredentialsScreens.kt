package com.acesoftph.offlinepasswordwallet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.acesoftph.offlinepasswordwallet.crypto.SecurityAnswers
import com.acesoftph.offlinepasswordwallet.data.repository.toUserMessage
import com.acesoftph.offlinepasswordwallet.di.ServiceLocator
import com.acesoftph.offlinepasswordwallet.password.PasswordStrength
import com.acesoftph.offlinepasswordwallet.ui.components.PasswordField
import com.acesoftph.offlinepasswordwallet.ui.components.StrengthMeter
import com.acesoftph.offlinepasswordwallet.ui.components.WalletCard
import kotlinx.coroutines.launch

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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PasswordField(current, { current = it; message = null }, "Current master password",
                modifier = Modifier.testTag("cmp_current"))
            PasswordField(next, { next = it; message = null }, "New master password",
                modifier = Modifier.testTag("cmp_new"))
            StrengthMeter(next)
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
                shape = RoundedCornerShape(14.dp),
                onClick = {
                    busy = true
                    val c = current.toCharArray(); val n = next.toCharArray(); val cf = confirm.toCharArray()
                    scope.launch {
                        val result = ServiceLocator.masterPasswordManager.changeMasterPassword(c, n, cf)
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WalletCard {
                Text(
                    "The five questions are fixed. Enter your current master password to authorise " +
                        "replacing the recovery answers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            PasswordField(master, { master = it; message = null }, "Current master password",
                modifier = Modifier.testTag("csa_master"))
            SecurityAnswers.QUESTIONS.forEachIndexed { index, q ->
                OutlinedTextField(
                    value = answers[index],
                    onValueChange = { answers[index] = it; message = null },
                    label = { Text("${index + 1}. $q") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
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
                shape = RoundedCornerShape(14.dp),
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
            Spacer(Modifier.height(24.dp))
        }
    }
}
