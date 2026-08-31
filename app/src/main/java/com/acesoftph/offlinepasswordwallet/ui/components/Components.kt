package com.acesoftph.offlinepasswordwallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acesoftph.offlinepasswordwallet.password.PasswordStrength
import com.acesoftph.offlinepasswordwallet.password.StrengthResult
import com.acesoftph.offlinepasswordwallet.ui.theme.LocalWalletPalette

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

/* --------------------------------------------------------------------------- */
/* Structure                                                                    */
/* --------------------------------------------------------------------------- */

/** Small caps section label with the brand accent bar to its left. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 16.dp)
                .background(LocalWalletPalette.current.accent, RoundedCornerShape(2.dp)),
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The tonal container every row and panel in the app sits in. */
@Composable
fun WalletCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = {
            Box(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
                content()
            }
        },
    )
}

/** Rounded-square tinted icon tile, as used down the left of every settings row. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color = LocalWalletPalette.current.accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A settings-style row: icon tile, title, optional subtitle, optional trailing. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = LocalWalletPalette.current.accent,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    WalletCard(modifier = modifier, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Circular tinted avatar carrying an entry's initial. */
@Composable
fun EntryAvatar(label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val palette = LocalWalletPalette.current
    val tints = palette.avatarTints
    // Stable per-title colour: the same entry keeps the same avatar between launches.
    val tint = tints[(label.lowercase().hashCode().mod(tints.size))]
    val initial = label.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier.size(size).background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = palette.onAvatar,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/* --------------------------------------------------------------------------- */
/* Password rendering                                                           */
/* --------------------------------------------------------------------------- */

/**
 * A generated password in monospace, with letters, digits and symbols in
 * different colours so the shape of the password is readable at a glance when
 * transcribing it by hand.
 */
@Composable
fun ColoredPassword(
    password: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
) {
    val palette = LocalWalletPalette.current
    val text: AnnotatedString = buildAnnotatedString {
        password.forEach { c ->
            val color = when {
                c.isDigit() -> palette.passwordDigit
                c.isLetter() -> palette.passwordLetter
                else -> palette.passwordSymbol
            }
            withStyle(SpanStyle(color = color)) { append(c) }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        lineHeight = fontSize * 1.35f,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Segmented strength meter. The filled segment count and the accompanying label
 * both come from [StrengthResult]; the label is never omitted, so the meaning
 * does not rest on colour alone.
 */
@Composable
fun StrengthMeter(
    password: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    segments: Int = 5,
) {
    val palette = LocalWalletPalette.current
    val result: StrengthResult = remember(password) { PasswordStrength.evaluate(password) }
    val filled = when {
        password.isEmpty() -> 0
        else -> (result.level.ordinal + 1).coerceIn(1, segments)
    }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(segments) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            if (i < filled) palette.strengthSteps[filled - 1] else palette.strengthTrack,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        if (showLabel) {
            Text(
                text = if (password.isEmpty()) "—" else "${result.level.label}  ·  ~${result.estimatedBits} bits",
                style = MaterialTheme.typography.bodySmall,
                color = if (password.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    palette.strengthSteps[filled - 1]
                },
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Small pill used for a status word such as "Strong" or a category name. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        modifier = modifier
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        fontWeight = FontWeight.SemiBold,
    )
}

/* --------------------------------------------------------------------------- */
/* Inputs                                                                       */
/* --------------------------------------------------------------------------- */

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
        shape = RoundedCornerShape(14.dp),
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

/** Kept for callers that only need the bar; delegates to [StrengthMeter]. */
@Composable
fun StrengthBar(password: String, modifier: Modifier = Modifier) =
    StrengthMeter(password, modifier)

/**
 * A read-only field on the Entry Details screen: label, value (masked if
 * sensitive and hidden), a reveal toggle for sensitive values, and a copy button
 * that always copies the REAL value.
 */
@Composable
fun CopyableFieldRow(
    label: String,
    value: String,
    sensitive: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    var revealed by remember(label) { mutableStateOf(false) }
    val shown = if (sensitive && !revealed) censored() else value

    WalletCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) Chip(badge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (shown.isEmpty()) "—" else shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (shown.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontFamily = if (sensitive) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
                if (sensitive) {
                    IconButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.semantics {
                            contentDescription = if (revealed) "Hide $label" else "Show $label"
                        },
                    ) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { onCopy(value) }, // always the real value
                    enabled = value.isNotEmpty(),
                    modifier = Modifier.semantics { contentDescription = "Copy $label" },
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sensitive && value.isNotEmpty()) {
                StrengthMeter(value, showLabel = false, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
