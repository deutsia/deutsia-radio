package com.opensource.i2pradio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Identifier for a color palette, matching the string values persisted by
 * [com.opensource.i2pradio.ui.PreferencesHelper.getColorScheme] and the
 * theme resource mapping in [com.opensource.i2pradio.MainActivity].
 */
enum class ColorSchemeName(val key: String) {
    Classic("classic"),
    Peach("peach"),
    Green("green"),
    Purple("purple"),
    Orange("orange"),
    Blue("blue");

    companion object {
        /**
         * Resolve a preference string to a [ColorSchemeName]. Defaults to
         * [Classic] for unknown values — matches the legacy "default" key and
         * the fallback behaviour in MainActivity.
         */
        fun fromKey(key: String?): ColorSchemeName = when (key) {
            "classic" -> Classic
            "peach" -> Peach
            "green" -> Green
            "purple" -> Purple
            "orange" -> Orange
            "blue" -> Blue
            else -> Classic
        }
    }
}

/**
 * Top-level Compose theme for the app.
 *
 * Mirrors the theme-resolution logic in
 * [com.opensource.i2pradio.MainActivity.onCreate]:
 *   1. If Material You is enabled on API 31+, use dynamic system colors.
 *   2. Otherwise, apply the user's chosen color scheme.
 *
 * @param colorSchemeName The palette to use when Material You is disabled or unavailable.
 * @param useDynamicColor Whether to use system Material You colors (API 31+).
 * @param darkTheme Whether to render the dark variant. Defaults to the system setting.
 */
@Composable
fun DeutsiaRadioTheme(
    colorSchemeName: ColorSchemeName = ColorSchemeName.Classic,
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkSchemeFor(colorSchemeName)
        else -> lightSchemeFor(colorSchemeName)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DeutsiaTypography,
        shapes = DeutsiaShapes,
        content = content,
    )
}

// ------------------------------------------------------------------
// Scheme builders
//
// The variant palettes (Peach, Green, Purple, Orange, Blue) only override
// primary/secondary roles, matching values/themes.xml and values-night/themes.xml.
// Everything else — tertiary, error, surface, surfaceContainer, outline, etc. —
// inherits from the Classic palette.
// ------------------------------------------------------------------

private fun classicLightScheme() = lightColorScheme(
    primary = ClassicLightPrimary,
    onPrimary = ClassicLightOnPrimary,
    primaryContainer = ClassicLightPrimaryContainer,
    onPrimaryContainer = ClassicLightOnPrimaryContainer,
    secondary = ClassicLightSecondary,
    onSecondary = ClassicLightOnSecondary,
    secondaryContainer = ClassicLightSecondaryContainer,
    onSecondaryContainer = ClassicLightOnSecondaryContainer,
    tertiary = ClassicLightTertiary,
    onTertiary = ClassicLightOnTertiary,
    tertiaryContainer = ClassicLightTertiaryContainer,
    onTertiaryContainer = ClassicLightOnTertiaryContainer,
    error = ClassicLightError,
    errorContainer = ClassicLightErrorContainer,
    onError = ClassicLightOnError,
    onErrorContainer = ClassicLightOnErrorContainer,
    background = ClassicLightBackground,
    onBackground = ClassicLightOnBackground,
    surface = ClassicLightSurface,
    onSurface = ClassicLightOnSurface,
    surfaceVariant = ClassicLightSurfaceVariant,
    onSurfaceVariant = ClassicLightOnSurfaceVariant,
    outline = ClassicLightOutline,
    outlineVariant = ClassicLightOutlineVariant,
    inverseOnSurface = ClassicLightInverseOnSurface,
    inverseSurface = ClassicLightInverseSurface,
    inversePrimary = ClassicLightInversePrimary,
    scrim = ClassicLightScrim,
    surfaceTint = ClassicLightSurfaceTint,
    surfaceContainer = ClassicLightSurfaceContainer,
    surfaceContainerLow = ClassicLightSurfaceContainerLow,
    surfaceContainerHigh = ClassicLightSurfaceContainerHigh,
    surfaceContainerHighest = ClassicLightSurfaceContainerHighest,
)

private fun classicDarkScheme() = darkColorScheme(
    primary = ClassicDarkPrimary,
    onPrimary = ClassicDarkOnPrimary,
    primaryContainer = ClassicDarkPrimaryContainer,
    onPrimaryContainer = ClassicDarkOnPrimaryContainer,
    secondary = ClassicDarkSecondary,
    onSecondary = ClassicDarkOnSecondary,
    secondaryContainer = ClassicDarkSecondaryContainer,
    onSecondaryContainer = ClassicDarkOnSecondaryContainer,
    tertiary = ClassicDarkTertiary,
    onTertiary = ClassicDarkOnTertiary,
    tertiaryContainer = ClassicDarkTertiaryContainer,
    onTertiaryContainer = ClassicDarkOnTertiaryContainer,
    error = ClassicDarkError,
    errorContainer = ClassicDarkErrorContainer,
    onError = ClassicDarkOnError,
    onErrorContainer = ClassicDarkOnErrorContainer,
    background = ClassicDarkBackground,
    onBackground = ClassicDarkOnBackground,
    surface = ClassicDarkSurface,
    onSurface = ClassicDarkOnSurface,
    surfaceVariant = ClassicDarkSurfaceVariant,
    onSurfaceVariant = ClassicDarkOnSurfaceVariant,
    outline = ClassicDarkOutline,
    outlineVariant = ClassicDarkOutlineVariant,
    inverseOnSurface = ClassicDarkInverseOnSurface,
    inverseSurface = ClassicDarkInverseSurface,
    inversePrimary = ClassicDarkInversePrimary,
    scrim = ClassicDarkScrim,
    surfaceTint = ClassicDarkSurfaceTint,
    surfaceContainer = ClassicDarkSurfaceContainer,
    surfaceContainerLow = ClassicDarkSurfaceContainerLow,
    surfaceContainerHigh = ClassicDarkSurfaceContainerHigh,
    surfaceContainerHighest = ClassicDarkSurfaceContainerHighest,
)

private fun lightSchemeFor(name: ColorSchemeName): ColorScheme {
    val base = classicLightScheme()
    return when (name) {
        ColorSchemeName.Classic -> base
        ColorSchemeName.Peach -> base.copy(
            primary = PeachLightPrimary,
            onPrimary = PeachLightOnPrimary,
            primaryContainer = PeachLightPrimaryContainer,
            onPrimaryContainer = PeachLightOnPrimaryContainer,
            secondary = PeachLightSecondary,
            onSecondary = PeachLightOnSecondary,
            secondaryContainer = PeachLightSecondaryContainer,
            onSecondaryContainer = PeachLightOnSecondaryContainer,
            surfaceTint = PeachLightPrimary,
        )
        ColorSchemeName.Green -> base.copy(
            primary = GreenLightPrimary,
            onPrimary = GreenLightOnPrimary,
            primaryContainer = GreenLightPrimaryContainer,
            onPrimaryContainer = GreenLightOnPrimaryContainer,
            secondary = GreenLightSecondary,
            onSecondary = GreenLightOnSecondary,
            secondaryContainer = GreenLightSecondaryContainer,
            onSecondaryContainer = GreenLightOnSecondaryContainer,
            surfaceTint = GreenLightPrimary,
        )
        ColorSchemeName.Purple -> base.copy(
            primary = PurpleLightPrimary,
            onPrimary = PurpleLightOnPrimary,
            primaryContainer = PurpleLightPrimaryContainer,
            onPrimaryContainer = PurpleLightOnPrimaryContainer,
            secondary = PurpleLightSecondary,
            onSecondary = PurpleLightOnSecondary,
            secondaryContainer = PurpleLightSecondaryContainer,
            onSecondaryContainer = PurpleLightOnSecondaryContainer,
            surfaceTint = PurpleLightPrimary,
        )
        ColorSchemeName.Orange -> base.copy(
            primary = OrangeLightPrimary,
            onPrimary = OrangeLightOnPrimary,
            primaryContainer = OrangeLightPrimaryContainer,
            onPrimaryContainer = OrangeLightOnPrimaryContainer,
            secondary = OrangeLightSecondary,
            onSecondary = OrangeLightOnSecondary,
            secondaryContainer = OrangeLightSecondaryContainer,
            onSecondaryContainer = OrangeLightOnSecondaryContainer,
            surfaceTint = OrangeLightPrimary,
        )
        ColorSchemeName.Blue -> base.copy(
            primary = BlueLightPrimary,
            onPrimary = BlueLightOnPrimary,
            primaryContainer = BlueLightPrimaryContainer,
            onPrimaryContainer = BlueLightOnPrimaryContainer,
            secondary = BlueLightSecondary,
            onSecondary = BlueLightOnSecondary,
            secondaryContainer = BlueLightSecondaryContainer,
            onSecondaryContainer = BlueLightOnSecondaryContainer,
            surfaceTint = BlueLightPrimary,
        )
    }
}

private fun darkSchemeFor(name: ColorSchemeName): ColorScheme {
    val base = classicDarkScheme()
    return when (name) {
        ColorSchemeName.Classic -> base
        ColorSchemeName.Peach -> base.copy(
            primary = PeachDarkPrimary,
            onPrimary = PeachDarkOnPrimary,
            primaryContainer = PeachDarkPrimaryContainer,
            onPrimaryContainer = PeachDarkOnPrimaryContainer,
            secondary = PeachDarkSecondary,
            onSecondary = PeachDarkOnSecondary,
            secondaryContainer = PeachDarkSecondaryContainer,
            onSecondaryContainer = PeachDarkOnSecondaryContainer,
            surfaceTint = PeachDarkPrimary,
        )
        ColorSchemeName.Green -> base.copy(
            primary = GreenDarkPrimary,
            onPrimary = GreenDarkOnPrimary,
            primaryContainer = GreenDarkPrimaryContainer,
            onPrimaryContainer = GreenDarkOnPrimaryContainer,
            secondary = GreenDarkSecondary,
            onSecondary = GreenDarkOnSecondary,
            secondaryContainer = GreenDarkSecondaryContainer,
            onSecondaryContainer = GreenDarkOnSecondaryContainer,
            surfaceTint = GreenDarkPrimary,
        )
        ColorSchemeName.Purple -> base.copy(
            primary = PurpleDarkPrimary,
            onPrimary = PurpleDarkOnPrimary,
            primaryContainer = PurpleDarkPrimaryContainer,
            onPrimaryContainer = PurpleDarkOnPrimaryContainer,
            secondary = PurpleDarkSecondary,
            onSecondary = PurpleDarkOnSecondary,
            secondaryContainer = PurpleDarkSecondaryContainer,
            onSecondaryContainer = PurpleDarkOnSecondaryContainer,
            surfaceTint = PurpleDarkPrimary,
        )
        ColorSchemeName.Orange -> base.copy(
            primary = OrangeDarkPrimary,
            onPrimary = OrangeDarkOnPrimary,
            primaryContainer = OrangeDarkPrimaryContainer,
            onPrimaryContainer = OrangeDarkOnPrimaryContainer,
            secondary = OrangeDarkSecondary,
            onSecondary = OrangeDarkOnSecondary,
            secondaryContainer = OrangeDarkSecondaryContainer,
            onSecondaryContainer = OrangeDarkOnSecondaryContainer,
            surfaceTint = OrangeDarkPrimary,
        )
        ColorSchemeName.Blue -> base.copy(
            primary = BlueDarkPrimary,
            onPrimary = BlueDarkOnPrimary,
            primaryContainer = BlueDarkPrimaryContainer,
            onPrimaryContainer = BlueDarkOnPrimaryContainer,
            secondary = BlueDarkSecondary,
            onSecondary = BlueDarkOnSecondary,
            secondaryContainer = BlueDarkSecondaryContainer,
            onSecondaryContainer = BlueDarkOnSecondaryContainer,
            surfaceTint = BlueDarkPrimary,
        )
    }
}
