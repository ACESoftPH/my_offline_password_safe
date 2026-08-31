package com.acesoftph.offlinepasswordwallet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * Brand palette: black, gray and orange.
 *
 * Dynamic colour is deliberately NOT used. It would replace this palette with
 * one derived from the user's wallpaper, which is the opposite of having a brand
 * colour at all.
 *
 * Orange is the single accent. Text on orange is near-black rather than white:
 * a vivid orange with white text lands around 2.5:1 contrast, which fails WCAG,
 * whereas black on the same orange clears 8:1.
 */

private val Orange = Color(0xFFFF7A1A)
private val OrangeBright = Color(0xFFFF8C33)
private val OrangeDim = Color(0xFFB4551A)
private val Amber = Color(0xFFFFB067)
private val AmberDeep = Color(0xFF9A5416)

private val Ink = Color(0xFF0A0A0A)
private val Charcoal = Color(0xFF121212)
private val Graphite = Color(0xFF1C1C1E)
private val Slate = Color(0xFF2A2A2D)
private val Steel = Color(0xFF3A3A3E)
private val Ash = Color(0xFF9A9A9E)
private val Mist = Color(0xFFE6E6E8)
private val Fog = Color(0xFFF2F2F4)
private val Paper = Color(0xFFFAFAFB)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Ink,
    primaryContainer = Color(0xFFFFE2CC),
    onPrimaryContainer = Color(0xFF3A1A00),
    secondary = Steel,
    onSecondary = Color.White,
    secondaryContainer = Mist,
    onSecondaryContainer = Ink,
    tertiary = AmberDeep,
    onTertiary = Color.White,
    background = Fog,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF5A5A5E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Paper,
    surfaceContainer = Color(0xFFEFEFF1),
    surfaceContainerHigh = Color(0xFFE9E9EC),
    surfaceContainerHighest = Mist,
    outline = Color(0xFFB6B6BA),
    outlineVariant = Color(0xFFD8D8DC),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = OrangeBright,
    onPrimary = Color(0xFF1A0C00),
    primaryContainer = Color(0xFF5A2A00),
    onPrimaryContainer = Color(0xFFFFE2CC),
    secondary = Ash,
    onSecondary = Ink,
    secondaryContainer = Slate,
    onSecondaryContainer = Mist,
    tertiary = Amber,
    onTertiary = Ink,
    background = Ink,
    onBackground = Color(0xFFF2F2F4),
    surface = Charcoal,
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Slate,
    onSurfaceVariant = Ash,
    surfaceContainerLowest = Color(0xFF060606),
    surfaceContainerLow = Charcoal,
    surfaceContainer = Graphite,
    surfaceContainerHigh = Slate,
    surfaceContainerHighest = Steel,
    outline = Color(0xFF55555A),
    outlineVariant = Color(0xFF33333722),
    error = Color(0xFFFF6B6B),
    onError = Ink,
)

/**
 * Colours the Material scheme has no slot for.
 *
 * Strength steps stay inside the brand palette — they run gray → dim orange →
 * orange → bright — instead of the usual red/amber/green ramp. Because that
 * progression is carried by saturation rather than hue, the meter is always
 * accompanied by a text label ("Weak", "Excellent"); colour alone never carries
 * the meaning.
 */
data class WalletPalette(
    val accent: Color,
    val strengthTrack: Color,
    val strengthSteps: List<Color>,
    val passwordLetter: Color,
    val passwordDigit: Color,
    val passwordSymbol: Color,
    val avatarTints: List<Color>,
    val onAvatar: Color,
)

private val LightPalette = WalletPalette(
    accent = Orange,
    strengthTrack = Color(0xFFDCDCE0),
    strengthSteps = listOf(Color(0xFF9A9A9E), Color(0xFFC98A4B), OrangeDim, Orange, OrangeBright),
    passwordLetter = Ink,
    passwordDigit = Color(0xFFC2410C),
    passwordSymbol = AmberDeep,
    avatarTints = listOf(
        Color(0xFFFF7A1A), Color(0xFFB4551A), Color(0xFF7A5C3E),
        Color(0xFF5A5A5E), Color(0xFF8A6A2A), Color(0xFF3A3A3E),
    ),
    onAvatar = Color.White,
)

private val DarkPalette = WalletPalette(
    accent = OrangeBright,
    strengthTrack = Color(0xFF2E2E32),
    strengthSteps = listOf(Color(0xFF6A6A6E), Color(0xFF8A6034), OrangeDim, Orange, OrangeBright),
    passwordLetter = Color(0xFFF2F2F4),
    passwordDigit = OrangeBright,
    passwordSymbol = Amber,
    avatarTints = listOf(
        Color(0xFFFF7A1A), Color(0xFFB4551A), Color(0xFF8A6A45),
        Color(0xFF55555A), Color(0xFFA07A32), Color(0xFF3A3A3E),
    ),
    onAvatar = Color(0xFF0A0A0A),
)

val LocalWalletPalette = staticCompositionLocalOf { DarkPalette }

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun OfflinePasswordWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val palette = if (darkTheme) DarkPalette else LightPalette

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalWalletPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
