package com.musify.mu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import com.musify.mu.playback.LocalMediaController

data class DynamicColors(val primary: Color, val secondary: Color, val surface: Color)

@Composable
fun MusifyTheme(dynamic: DynamicColors? = null, content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val controller = LocalMediaController.current
    val context = LocalContext.current
    val dynamicFromArt = remember { mutableStateOf<DynamicColors?>(null) }
    val themeManager = remember { AppThemeManager.getInstance(context) }
    
    // Check if custom theme is enabled
    val useCustomTheme = remember { themeManager.useCustomTheme }
    val customColors = remember {
        if (useCustomTheme) {
            DynamicColors(
                primary = Color(themeManager.customPrimaryColor),
                secondary = Color(themeManager.customSecondaryColor),
                surface = Color(themeManager.customBackgroundColor)
            )
        } else null
    }

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

    // Priority: 1. Explicitly passed dynamic, 2. Custom theme, 3. Dynamic from artwork
    val appliedDynamic = dynamic ?: customColors ?: dynamicFromArt.value
    val colorScheme = when {
        appliedDynamic != null -> {
            if (darkTheme) darkColorScheme(
                primary = appliedDynamic.primary,
                secondary = appliedDynamic.secondary,
                tertiary = NeonPink,
                background = DarkBackground,
                surface = appliedDynamic.surface,
                surfaceVariant = DarkSurfaceVariant,
                onPrimary = SpotifyWhite,
                onSecondary = SpotifyWhite,
                onTertiary = SpotifyWhite,
                outline = DarkOutline
            ) else lightColorScheme(
                primary = appliedDynamic.primary,
                secondary = appliedDynamic.secondary,
                tertiary = NeonPink,
                background = ModernBackground,
                surface = appliedDynamic.surface,
                surfaceVariant = ModernSurfaceVariant,
                onPrimary = SpotifyWhite,
                onSecondary = SpotifyWhite,
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = shapes,
        content = content
    )
}
