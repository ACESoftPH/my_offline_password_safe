package com.acesoft.offlinepasswordwallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.acesoft.offlinepasswordwallet.password.PasswordStrength
import com.acesoft.offlinepasswordwallet.password.StrengthResult

/**
 * The censor rendering used everywhere a hidden password is shown.
 *
 * Both the editable field's mask and the read-only detail row derive from this
 * single character, so the two can never disagree about what a hidden password
 * looks like.
 */
const val CENSOR_CHAR = '*'

/** Fixed length on purpose: a hidden value must not leak how long it really is. */
private const val CENSOR_DISPLAY_LENGTH = 10
private val CENSOR_DISPLAY = CENSOR_CHAR.toString().repeat(CENSOR_DISPLAY_LENGTH)

fun censored(): String = CENSOR_DISPLAY

/**
 * Password text field with a show/hide toggle. The [value] passed to [onValueChange]
 * is ALWAYS the real text; the transformation only affects rendering.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    initiallyVisible: Boolean = false,
    trailingExtra: @Composable (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(initiallyVisible) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation(mask = CENSOR_CHAR)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                IconButton(
                    onClick = { visible = !visible },
                    modifier = Modifier.semantics {
                        contentDescription = if (visible) "Hide $label" else "Show $label"
                    },
                ) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
                trailingExtra?.invoke()
            }
        },
    )
}

@Composable
fun StrengthBar(password: String, modifier: Modifier = Modifier) {
    val result: StrengthResult = remember(password) { PasswordStrength.evaluate(password) }
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { result.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Strength: ${result.level.label}  (~${result.estimatedBits} bits)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A read-only row in the Entry Details screen: label, value (masked if sensitive
 * and hidden), a SHOW toggle for sensitive values, and a COPY button that always
 * copies the REAL value (§7, §8).
 */
@Composable
fun CopyableFieldRow(
    label: String,
    value: String,
    sensitive: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(label) { mutableStateOf(false) }
    val shown = if (sensitive && !revealed) censored() else value

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (shown.isEmpty()) "—" else shown,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (sensitive) {
            TextButton(onClick = { revealed = !revealed }) {
                Text(if (revealed) "HIDE" else "SHOW")
            }
        }
        IconButton(
            onClick = { onCopy(value) }, // always the real value
            enabled = value.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = "Copy $label" },
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
        }
    }
}
