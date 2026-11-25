package com.aileboeth.hush.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Apple Human Interface Guidelines–inspired minimalist color tokens.
 * Clean neutral canvas, pure elevated cards, Apple System Blue primary accent,
 * and refined Apple Intelligence iridescent hues reserved exclusively for hero glow accents.
 */
@Immutable
data class HushColors(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val surfaceElevated: Color,
    val separator: Color,
    val glassBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Apple semantic accents
    val blue: Color,
    val blueSoft: Color,
    val onBlue: Color,
    val indigo: Color,
    val indigoSoft: Color,
    val purple: Color,
    val cyan: Color,
    val green: Color,
    val greenSoft: Color,
    val orange: Color,
    val orangeSoft: Color,
    val red: Color,
    val redSoft: Color,
    val neutral: Color,
    val neutralSoft: Color,
    val dockBackground: Color,
    val dockBorder: Color,
    val scrim: Color,
    val trackBg: Color,
)

private val AppleLightColors = HushColors(
    isDark = false,
    canvas = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2F2F7),
    surfaceElevated = Color(0xFFFFFFFF),
    separator = Color(0x1F3C3C43),
    glassBorder = Color(0x17000000),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF6E6E73),
    textTertiary = Color(0xFF8E8E93),
    blue = Color(0xFF007AFF),
    blueSoft = Color(0x18007AFF),
    onBlue = Color(0xFFFFFFFF),
    indigo = Color(0xFF5856D6),
    indigoSoft = Color(0x185856D6),
    purple = Color(0xFFAF52DE),
    cyan = Color(0xFF32ADE6),
    green = Color(0xFF34C759),
    greenSoft = Color(0x1A34C759),
    orange = Color(0xFFFF9500),
    orangeSoft = Color(0x1AFF9500),
    red = Color(0xFFFF3B30),
    redSoft = Color(0x18FF3B30),
    neutral = Color(0xFF8E8E93),
    neutralSoft = Color(0x168E8E93),
    dockBackground = Color(0xF5FFFFFF),
    dockBorder = Color(0x1A000000),
    scrim = Color(0x52000000),
    trackBg = Color(0xFFE5E5EA),
)

private val AppleDarkColors = HushColors(
    isDark = true,
    canvas = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceMuted = Color(0xFF2C2C2E),
    surfaceElevated = Color(0xFF2C2C2E),
    separator = Color(0x2EFFFFFF),
    glassBorder = Color(0x24FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989D),
    textTertiary = Color(0xFF636366),
    blue = Color(0xFF0A84FF),
    blueSoft = Color(0x290A84FF),
    onBlue = Color(0xFFFFFFFF),
    indigo = Color(0xFF5E5CE6),
    indigoSoft = Color(0x295E5CE6),
    purple = Color(0xFFBF5AF2),
    cyan = Color(0xFF64D2FF),
    green = Color(0xFF30D158),
    greenSoft = Color(0x2630D158),
    orange = Color(0xFFFF9F0A),
    orangeSoft = Color(0x26FF9F0A),
    red = Color(0xFFFF453A),
    redSoft = Color(0x26FF453A),
    neutral = Color(0xFF8E8E93),
    neutralSoft = Color(0x248E8E93),
    dockBackground = Color(0xF21C1C1E),
    dockBorder = Color(0x2AFFFFFF),
    scrim = Color(0x8C000000),
    trackBg = Color(0xFF3A3A3C),
)

private val LocalHushColors = staticCompositionLocalOf { AppleLightColors }

object HushType {
    val largeTitle = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    )

    val heroNumber = TextStyle(
        fontSize = 42.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.4).sp,
        fontFeatureSettings = "tnum",
    )

    val metricNumber = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    )

    val compactNumber = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

    val sectionTitle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    )

    val cardTitle = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )

    val body = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    )

    val caption = TextStyle(
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    )

    val micro = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    )

    val monoCode = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    )
}

object HushTheme {
    val colors: HushColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHushColors.current
}

@Composable
fun HushTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) AppleDarkColors else AppleLightColors

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.blue,
            onPrimary = palette.onBlue,
            primaryContainer = palette.blueSoft,
            onPrimaryContainer = palette.blue,
            secondary = palette.indigo,
            onSecondary = Color.White,
            secondaryContainer = palette.indigoSoft,
            onSecondaryContainer = palette.indigo,
            tertiary = palette.green,
            error = palette.red,
            errorContainer = palette.redSoft,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceMuted,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.separator,
            outlineVariant = palette.glassBorder,
        )
    } else {
        lightColorScheme(
            primary = palette.blue,
            onPrimary = palette.onBlue,
            primaryContainer = palette.blueSoft,
            onPrimaryContainer = palette.blue,
            secondary = palette.indigo,
            onSecondary = Color.White,
            secondaryContainer = palette.indigoSoft,
            onSecondaryContainer = palette.indigo,
            tertiary = palette.green,
            error = palette.red,
            errorContainer = palette.redSoft,
            background = palette.canvas,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceMuted,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.separator,
            outlineVariant = palette.glassBorder,
        )
    }

    val typography = Typography(
        headlineMedium = HushType.largeTitle,
        titleMedium = HushType.sectionTitle,
        titleSmall = HushType.cardTitle,
        bodyMedium = HushType.body,
        labelMedium = HushType.caption,
        labelSmall = HushType.micro,
    )

    CompositionLocalProvider(LocalHushColors provides palette) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = typography,
            content = content,
        )
    }
}
