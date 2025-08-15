package com.musify.mu.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager class for handling theme and UI customization settings
 */
class AppThemeManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Theme settings
    val useCustomTheme: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM_THEME, false)
    
    val customPrimaryColor: Int
        get() = prefs.getInt(KEY_CUSTOM_PRIMARY_COLOR, SpotifyGreen.toArgb())
    
    val customSecondaryColor: Int
        get() = prefs.getInt(KEY_CUSTOM_SECONDARY_COLOR, ElectricBlue.toArgb())
    
    val customBackgroundColor: Int
        get() = prefs.getInt(KEY_CUSTOM_BACKGROUND_COLOR, DarkBackground.toArgb())
    
    // Layout settings
    val customLayoutEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_LAYOUT_ENABLED, false)
    
    val homeLayoutConfig: List<String>
        get() {
            val defaultLayout = listOf("welcome", "recentlyPlayed", "recentlyAdded", "favorites", "playlists")
            val savedLayout = prefs.getString(KEY_HOME_LAYOUT_CONFIG, null)
            return savedLayout?.split(",") ?: defaultLayout
        }
    
    // Animation settings
    val useAnimatedBackgrounds: Boolean
        get() = prefs.getBoolean(KEY_USE_ANIMATED_BACKGROUNDS, false)
    
    val animationStyle: String
        get() = prefs.getString(KEY_ANIMATION_STYLE, "waves") ?: "waves"
    
    // Theme settings methods
    suspend fun setUseCustomTheme(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USE_CUSTOM_THEME, enabled).apply()
    }
    
    suspend fun setCustomColors(colors: DynamicColors) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putInt(KEY_CUSTOM_PRIMARY_COLOR, colors.primary.toArgb())
            .putInt(KEY_CUSTOM_SECONDARY_COLOR, colors.secondary.toArgb())
            .putInt(KEY_CUSTOM_BACKGROUND_COLOR, colors.surface.toArgb())
            .apply()
    }
    
    // Layout settings methods
    suspend fun setCustomLayoutEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_CUSTOM_LAYOUT_ENABLED, enabled).apply()
    }
    
    suspend fun setHomeLayoutConfig(config: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_HOME_LAYOUT_CONFIG, config.joinToString(",")).apply()
    }
    
    // Animation settings methods
    suspend fun setUseAnimatedBackgrounds(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USE_ANIMATED_BACKGROUNDS, enabled).apply()
    }
    
    suspend fun setAnimationStyle(style: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_ANIMATION_STYLE, style).apply()
    }
    
    companion object {
        private const val PREFS_NAME = "musify_theme_prefs"
        
        // Theme keys
        private const val KEY_USE_CUSTOM_THEME = "use_custom_theme"
        private const val KEY_CUSTOM_PRIMARY_COLOR = "custom_primary_color"
        private const val KEY_CUSTOM_SECONDARY_COLOR = "custom_secondary_color"
        private const val KEY_CUSTOM_BACKGROUND_COLOR = "custom_background_color"
        
        // Layout keys
        private const val KEY_CUSTOM_LAYOUT_ENABLED = "custom_layout_enabled"
        private const val KEY_HOME_LAYOUT_CONFIG = "home_layout_config"
        
        // Animation keys
        private const val KEY_USE_ANIMATED_BACKGROUNDS = "use_animated_backgrounds"
        private const val KEY_ANIMATION_STYLE = "animation_style"
        
        @Volatile
        private var instance: AppThemeManager? = null
        
        fun getInstance(context: Context): AppThemeManager {
            return instance ?: synchronized(this) {
                instance ?: AppThemeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
