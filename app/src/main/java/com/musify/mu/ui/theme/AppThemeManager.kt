package com.musify.mu.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager class for handling theme and UI customization settings
 */
class AppThemeManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Backing Compose state for instant UI updates
    private val _useCustomTheme = mutableStateOf(prefs.getBoolean(KEY_USE_CUSTOM_THEME, false))
    private val _useSystemDynamic = mutableStateOf(prefs.getBoolean(KEY_USE_SYSTEM_DYNAMIC, false))
    private val _customPrimaryColor = mutableStateOf(prefs.getInt(KEY_CUSTOM_PRIMARY_COLOR, SpotifyGreen.toArgb()))
    private val _customSecondaryColor = mutableStateOf(prefs.getInt(KEY_CUSTOM_SECONDARY_COLOR, ElectricBlue.toArgb()))
    private val _customBackgroundColor = mutableStateOf(prefs.getInt(KEY_CUSTOM_BACKGROUND_COLOR, DarkBackground.toArgb()))
    private val _fontFamilyKey = mutableStateOf(prefs.getString(KEY_FONT_FAMILY, "sans") ?: "sans")

    // Theme settings (Compose states)
    val useCustomThemeState: State<Boolean> get() = _useCustomTheme
    val useSystemDynamicColorsState: State<Boolean> get() = _useSystemDynamic
    val customPrimaryColorState: State<Int> get() = _customPrimaryColor
    val customSecondaryColorState: State<Int> get() = _customSecondaryColor
    val customBackgroundColorState: State<Int> get() = _customBackgroundColor
    val fontFamilyKeyState: State<String> get() = _fontFamilyKey
    
    // Layout settings
    private val _customLayoutEnabled = mutableStateOf(prefs.getBoolean(KEY_CUSTOM_LAYOUT_ENABLED, false))
    val customLayoutEnabled: Boolean
        get() = _customLayoutEnabled.value
    
    val homeLayoutConfig: List<String>
        get() {
            val defaultLayout = listOf("welcome", "recentlyPlayed", "recentlyAdded", "favorites", "playlists")
            val savedLayout = prefs.getString(KEY_HOME_LAYOUT_CONFIG, null)
            return savedLayout?.split(",") ?: defaultLayout
        }
    
    // Animation settings
    private val _useAnimatedBackgrounds = mutableStateOf(prefs.getBoolean(KEY_USE_ANIMATED_BACKGROUNDS, false))
    val useAnimatedBackgrounds: Boolean
        get() = _useAnimatedBackgrounds.value
    
    private val _animationStyle = mutableStateOf(prefs.getString(KEY_ANIMATION_STYLE, "waves") ?: "waves")
    val animationStyle: String
        get() = _animationStyle.value
    
    // Theme settings methods
    suspend fun setUseCustomTheme(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USE_CUSTOM_THEME, enabled).apply()
        _useCustomTheme.value = enabled
        if (enabled) {
            // turning off system dynamic to avoid conflict
            prefs.edit().putBoolean(KEY_USE_SYSTEM_DYNAMIC, false).apply()
            _useSystemDynamic.value = false
        }
    }
    
    suspend fun setUseSystemDynamicColors(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USE_SYSTEM_DYNAMIC, enabled).apply()
        _useSystemDynamic.value = enabled
        if (enabled) {
            // turning off custom theme to avoid conflict
            prefs.edit().putBoolean(KEY_USE_CUSTOM_THEME, false).apply()
            _useCustomTheme.value = false
        }
    }
    
    suspend fun setCustomColors(colors: DynamicColors) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putInt(KEY_CUSTOM_PRIMARY_COLOR, colors.primary.toArgb())
            .putInt(KEY_CUSTOM_SECONDARY_COLOR, colors.secondary.toArgb())
            .putInt(KEY_CUSTOM_BACKGROUND_COLOR, colors.surface.toArgb())
            .apply()
        _customPrimaryColor.value = colors.primary.toArgb()
        _customSecondaryColor.value = colors.secondary.toArgb()
        _customBackgroundColor.value = colors.surface.toArgb()
    }
    
    // Layout settings methods
    suspend fun setCustomLayoutEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_CUSTOM_LAYOUT_ENABLED, enabled).apply()
        _customLayoutEnabled.value = enabled
    }
    
    suspend fun setHomeLayoutConfig(config: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_HOME_LAYOUT_CONFIG, config.joinToString(",")).apply()
    }
    
    // Animation settings methods
    suspend fun setUseAnimatedBackgrounds(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USE_ANIMATED_BACKGROUNDS, enabled).apply()
        _useAnimatedBackgrounds.value = enabled
    }
    
    suspend fun setAnimationStyle(style: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_ANIMATION_STYLE, style).apply()
        _animationStyle.value = style
    }

    suspend fun setFontFamilyKey(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_FONT_FAMILY, key).apply()
        _fontFamilyKey.value = key
    }
    
    companion object {
        private const val PREFS_NAME = "musify_theme_prefs"
        
        // Theme keys
        private const val KEY_USE_CUSTOM_THEME = "use_custom_theme"
        private const val KEY_USE_SYSTEM_DYNAMIC = "use_system_dynamic"
        private const val KEY_CUSTOM_PRIMARY_COLOR = "custom_primary_color"
        private const val KEY_CUSTOM_SECONDARY_COLOR = "custom_secondary_color"
        private const val KEY_CUSTOM_BACKGROUND_COLOR = "custom_background_color"
        private const val KEY_FONT_FAMILY = "font_family_key"
        
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
