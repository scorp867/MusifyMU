package com.musify.mu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import androidx.core.graphics.ColorUtils
import android.os.Build
import coil.ImageLoader
import coil.request.ImageRequest
import com.musify.mu.playback.LocalMediaController

data class DynamicColors(val primary: Color, val secondary: Color, val surface: Color)

// Helpers to ensure readable text on theme colors
private fun bestOnColor(color: Color): Color {
    val y = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return if (y < 0.5f) SpotifyWhite else SpotifyBlack
}

private fun ensureReadableBackground(
    surface: Color,
    fallbackDark: Color = DarkBackground,
    fallbackLight: Color = ModernBackground
): Color {
    // For now, return provided surface; hook for future WCAG adjustments
    return surface
}

@Composable
fun MusifyTheme(dynamic: DynamicColors? = null, content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val controller = LocalMediaController.current
    val context = LocalContext.current
    val dynamicFromArt = remember { mutableStateOf<DynamicColors?>(null) }
    val themeManager = remember { AppThemeManager.getInstance(context) }
    
    // Check if custom theme is enabled
    val useCustomTheme = themeManager.useCustomThemeState.value
    val useSystemDynamic = themeManager.useSystemDynamicColorsState.value
    val primaryColor = themeManager.customPrimaryColorState.value
    val secondaryColor = themeManager.customSecondaryColorState.value
    val backgroundColor = themeManager.customBackgroundColorState.value
    val customColors = if (useCustomTheme) {
        DynamicColors(
            primary = Color(primaryColor),
            secondary = Color(secondaryColor),
            surface = Color(backgroundColor)
        )
    } else null

    LaunchedEffect(controller?.currentMediaItem) {
        // Skip dynamic color extraction if using custom theme
        if (useCustomTheme) return@LaunchedEffect
        
        val artUri = controller?.currentMediaItem?.mediaMetadata?.artworkUri
        if (artUri != null) {
            val req = ImageRequest.Builder(context).data(artUri).allowHardware(false).build()
            val drawable = ImageLoader(context).execute(req).drawable
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val palette = Palette.from(bitmap).generate()
                // Enhanced palette extraction for more vibrant colors
                val primary = Color(palette.getVibrantColor(SpotifyGreen.toArgb()))
                val secondary = Color(palette.getLightVibrantColor(ElectricBlue.toArgb()))
                val surface = Color(palette.getDarkMutedColor(DarkSurface.toArgb()))
                dynamicFromArt.value = DynamicColors(primary, secondary, surface)
            }
        }
    }

    // Priority:
    // 1) System dynamic (Material You) on Android 12+ when enabled
    // 2) Explicit dynamic passed in
    // 3) Custom colors
    // 4) Artwork-derived dynamic
    val systemDynamicScheme = if (useSystemDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null

    val appliedDynamic = when {
        dynamic != null -> dynamic
        customColors != null -> customColors
        else -> dynamicFromArt.value
    }

    val colorScheme = when {
        systemDynamicScheme != null -> systemDynamicScheme
        appliedDynamic != null -> {
            if (darkTheme) darkColorScheme(
                primary = appliedDynamic.primary,
                secondary = appliedDynamic.secondary,
                tertiary = NeonPink,
                background = ensureReadableBackground(appliedDynamic.surface, fallbackDark = DarkBackground),
                surface = appliedDynamic.surface,
                surfaceVariant = DarkSurfaceVariant,
                onPrimary = bestOnColor(appliedDynamic.primary),
                onSecondary = bestOnColor(appliedDynamic.secondary),
                onTertiary = SpotifyWhite,
                outline = DarkOutline
            ) else lightColorScheme(
                primary = appliedDynamic.primary,
                secondary = appliedDynamic.secondary,
                tertiary = NeonPink,
                background = ensureReadableBackground(appliedDynamic.surface, fallbackLight = ModernBackground),
                surface = appliedDynamic.surface,
                surfaceVariant = ModernSurfaceVariant,
                onPrimary = bestOnColor(appliedDynamic.primary),
                onSecondary = bestOnColor(appliedDynamic.secondary),
                onTertiary = SpotifyWhite,
                outline = ModernOutline
            )
        }
        darkTheme -> darkColorScheme(
            primary = SpotifyGreen,
            secondary = ElectricBlue,
            tertiary = VibrantPurple,
            background = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = DarkSurfaceVariant,
            onPrimary = SpotifyWhite,
            onSecondary = SpotifyWhite,
            onTertiary = SpotifyWhite,
            onBackground = SpotifyWhite,
            onSurface = SpotifyWhite,
            outline = DarkOutline
        )
        else -> lightColorScheme(
            primary = SpotifyGreen,
            secondary = ElectricBlue,
            tertiary = VibrantPurple,
            background = ModernBackground,
            surface = ModernSurface,
            surfaceVariant = ModernSurfaceVariant,
            onPrimary = SpotifyWhite,
            onSecondary = SpotifyWhite,
            onTertiary = SpotifyWhite,
            onBackground = SpotifyBlack,
            onSurface = SpotifyBlack,
            outline = ModernOutline
        )
    }

    // Custom shapes for a more futuristic look
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp)
    )

    // Dynamic font selection
    val fontKey = themeManager.fontFamilyKeyState.value
    val appFontFamily = provideAppFontFamily(fontKey)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = appTypography(appFontFamily),
        shapes = shapes
    ) {
        androidx.compose.material3.Surface(color = colorScheme.background) {
            // Optional global glass/backdrop layer
            androidx.compose.foundation.layout.Box {
                com.musify.mu.ui.components.GlassBackdrop(
                    tintTop = if (darkTheme) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.12f),
                    tintBottom = if (darkTheme) Color.Black.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f),
                    blurRadius = if (darkTheme) 18 else 14
                )
                content()
            }
        }
    }
}
