package com.musify.mu.ui.screens


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.musify.mu.ui.theme.AppThemeManager
import com.musify.mu.ui.theme.DynamicColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val themeManager = remember { AppThemeManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    
    // State for settings
    val useCustomTheme by themeManager.useCustomThemeState
    val dynamicEnabled by themeManager.useSystemDynamicColorsState
    var customPrimaryColor by remember { mutableStateOf(Color(themeManager.customPrimaryColorState.value)) }
    var customSecondaryColor by remember { mutableStateOf(Color(themeManager.customSecondaryColorState.value)) }
    var customBackgroundColor by remember { mutableStateOf(Color(themeManager.customBackgroundColorState.value)) }
    // Live preview: persist immediately when colors change, and ensure custom theme is on
    LaunchedEffect(customPrimaryColor, customSecondaryColor, customBackgroundColor, dynamicEnabled, useCustomTheme) {
        if (useCustomTheme && !dynamicEnabled) {
            scope.launch {
                themeManager.setCustomColors(
                    DynamicColors(
                        primary = customPrimaryColor,
                        secondary = customSecondaryColor,
                        surface = customBackgroundColor
                    )
                )
            }
        }
    }

    var useAnimatedBackgrounds by remember { mutableStateOf(themeManager.useAnimatedBackgrounds) }
    var customLayoutEnabled by remember { mutableStateOf(themeManager.customLayoutEnabled) }
    val homeLayoutConfigState by remember { mutableStateOf(themeManager.homeLayoutConfigState) }
    var homeLayoutConfig by remember { mutableStateOf(homeLayoutConfigState.value) }
    
    // Color picker state
    var showColorPicker by remember { mutableStateOf(false) }
    var currentColorEditTarget by remember { mutableStateOf<String?>(null) }
    
    // Section expansion states
    var themeExpanded by remember { mutableStateOf(false) }
    var layoutExpanded by remember { mutableStateOf(false) }
    var animationExpanded by remember { mutableStateOf(false) }
    var fontsExpanded by remember { mutableStateOf(false) }
    
    // Background gradient
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navController.navigateUp() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                

                Spacer(modifier = Modifier.width(16.dp))
                
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            
            // Settings content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Fonts section (properly inside list)
                item {
                    SettingsSection(
                        title = "Fonts",
                        icon = Icons.Rounded.FontDownload,
                        isExpanded = fontsExpanded,
                        onExpandToggle = { fontsExpanded = !fontsExpanded }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val currentKey by themeManager.fontFamilyKeyState
                            val options = listOf(
                                "inter" to "Inter",
                                "outfit" to "Outfit",
                                "poppins" to "Poppins",
                                "playfair" to "Playfair Display",
                                "jetbrains_mono" to "JetBrains Mono"
                            )
                            options.forEach { (key, label) ->
                                val selected = currentKey == key
                                val previewFamily = com.musify.mu.ui.theme.provideAppFontFamily(key)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { scope.launch { themeManager.setFontFamilyKey(key) } }
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = { scope.launch { themeManager.setFontFamilyKey(key) } })
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(text = label, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "The quick brown fox jumps over the lazy dog.",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewFamily),
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                        )
                                    }
                                    if (selected) {
                                        Icon(imageVector = Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Theme customization section
                item {
                    SettingsSection(
                        title = "Theme Customization",
                        icon = Icons.Rounded.Palette,
                        isExpanded = themeExpanded,
                        onExpandToggle = { themeExpanded = !themeExpanded }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // System dynamic colors toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Use System Dynamic Colors",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Switch(
                                    checked = dynamicEnabled,
                                    onCheckedChange = {
                                        scope.launch { themeManager.setUseSystemDynamicColors(it) }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }

                            // Custom theme toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Use Custom Theme",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                
                                Switch(
                                    checked = useCustomTheme,
                                    onCheckedChange = {
                                        scope.launch {
                                            themeManager.setUseCustomTheme(it)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                            
                            AnimatedVisibility(visible = useCustomTheme) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Primary color
                                    ColorPickerRow(
                                        title = "Primary Color",
                                        color = customPrimaryColor,
                                        onClick = {
                                            currentColorEditTarget = "primary"
                                            showColorPicker = true
                                        }
                                    )
                                    
                                    // Secondary color
                                    ColorPickerRow(
                                        title = "Secondary Color",
                                        color = customSecondaryColor,
                                        onClick = {
                                            currentColorEditTarget = "secondary"
                                            showColorPicker = true
                                        }
                                    )
                                    
                                    // Background color
                                    ColorPickerRow(
                                        title = "Background Color",
                                        color = customBackgroundColor,
                                        onClick = {
                                            currentColorEditTarget = "background"
                                            showColorPicker = true
                                        }
                                    )

                                    // Preset color schemes
                                    Text(
                                        text = "Presets",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    val presets = listOf(
                                        // 6 elegant, high-contrast dark-toned schemes (no light backgrounds)
                                        DynamicColors(Color(0xFF1E40AF), Color(0xFFEAB308), Color(0xFF0C1B3A)), // Royal Blue / Gold / Deep Navy
                                        DynamicColors(Color(0xFF6D28D9), Color(0xFFF59E0B), Color(0xFF1A1033)), // Imperial Purple / Amber / Midnight Purple
                                        DynamicColors(Color(0xFFB91C1C), Color(0xFFB45309), Color(0xFF2A0E12)), // Crimson / Bronze / Deep Maroon
                                        DynamicColors(Color(0xFF0D9488), Color(0xFF2563EB), Color(0xFF0B2E2B)), // Teal / Azure / Dark Teal
                                        DynamicColors(Color(0xFF065F46), Color(0xFF9333EA), Color(0xFF0F1C16)), // Emerald / Amethyst / Forest Night
                                        DynamicColors(Color(0xFF4338CA), Color(0xFF06B6D4), Color(0xFF0E1A24))  // Indigo / Cyan / Charcoal Blue
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        presets.forEach { preset ->
                                            val selected = preset.primary == customPrimaryColor &&
                                                preset.secondary == customSecondaryColor &&
                                                preset.surface == customBackgroundColor
                                            ColorPresetSwatch(
                                                preset = preset,
                                                selected = selected,
                                                onClick = {
                                                    customPrimaryColor = preset.primary
                                                    customSecondaryColor = preset.secondary
                                                    customBackgroundColor = preset.surface
                                                }
                                            )
                                        }
                                    }
                                    
                                    // Apply button
                                    val applied = useCustomTheme &&
                                        Color(themeManager.customPrimaryColorState.value) == customPrimaryColor &&
                                        Color(themeManager.customSecondaryColorState.value) == customSecondaryColor &&
                                        Color(themeManager.customBackgroundColorState.value) == customBackgroundColor

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                themeManager.setCustomColors(
                                                    DynamicColors(
                                                        primary = customPrimaryColor,
                                                        secondary = customSecondaryColor,
                                                        surface = customBackgroundColor
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !applied && !dynamicEnabled,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text(if (applied) "Applied" else "Apply Theme")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Layout customization section
                item {
                    SettingsSection(
                        title = "Layout Customization",
                        icon = Icons.Rounded.ViewModule,
                        isExpanded = layoutExpanded,
                        onExpandToggle = { layoutExpanded = !layoutExpanded }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Custom layout toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Custom Home Layout",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                
                                Switch(
                                    checked = customLayoutEnabled,
                                    onCheckedChange = {
                                        customLayoutEnabled = it
                                        scope.launch {
                                            themeManager.setCustomLayoutEnabled(it)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                            
                            AnimatedVisibility(visible = customLayoutEnabled) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Layout options (welcome header is fixed and not configurable)
                                    val sections = listOf(
                                        "recentlyPlayed" to "Recently Played",
                                        "recentlyAdded" to "Recently Added",
                                        "favorites" to "Favorites",
                                        "playlists" to "Playlists"
                                    )
                                    
                                    sections.forEach { (key, name) ->
                                        DraggableLayoutItem(
                                            title = name,
                                            enabled = homeLayoutConfig.contains(key),
                                            onToggle = { enabled ->
                                                homeLayoutConfig = if (enabled) {
                                                    homeLayoutConfig + key
                                                } else {
                                                    homeLayoutConfig.filter { it != key }
                                                }
                                                scope.launch {
                                                    themeManager.setHomeLayoutConfig(homeLayoutConfig)
                                                }
                                            }
                                        )
                                    }
                                    
                                    // Reset button
                                    TextButton(
                                        onClick = {
                                            homeLayoutConfig = listOf(
                                                "recentlyPlayed", "recentlyAdded", "favorites", "playlists"
                                            )
                                            scope.launch {
                                                themeManager.setHomeLayoutConfig(homeLayoutConfig)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Reset to Default")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Animation settings section
                item {
                    SettingsSection(
                        title = "Animation Settings",
                        icon = Icons.Rounded.Animation,
                        isExpanded = animationExpanded,
                        onExpandToggle = { animationExpanded = !animationExpanded }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Animated backgrounds toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Animated Backgrounds",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                
                                Switch(
                                    checked = useAnimatedBackgrounds,
                                    onCheckedChange = {
                                        useAnimatedBackgrounds = it
                                        scope.launch {
                                            themeManager.setUseAnimatedBackgrounds(it)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                            
                            // Animation style options
                            AnimatedVisibility(visible = useAnimatedBackgrounds) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val animationStyles = listOf(
                                        "waves" to "Wave Effect",
                                        "particles" to "Particle Effect",
                                        "pulse" to "Pulse Effect",
                                        "neon_grid" to "Neon Grid",
                                        "orbit_rings" to "Orbit Rings"
                                    )
                                    
                                    var selectedAnimation by remember { 
                                        mutableStateOf(themeManager.animationStyle) 
                                    }
                                    
                                    animationStyles.forEach { (key, name) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedAnimation = key
                                                    scope.launch {
                                                        themeManager.setAnimationStyle(key)
                                                    }
                                                }
                                                .background(
                                                    if (selectedAnimation == key)
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    else
                                                        Color.Transparent
                                                )
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = selectedAnimation == key,
                                                onClick = {
                                                    selectedAnimation = key
                                                    scope.launch {
                                                        themeManager.setAnimationStyle(key)
                                                    }
                                                }
                                            )
                                            
                                            Spacer(modifier = Modifier.width(8.dp))
                                            
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onBackground
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                // Inline animated preview (pauses when playback paused)
                                                com.musify.mu.ui.components.AnimatedBackgroundPreview(
                                                    styleKey = key,
                                                    isPlaying = true,
                                                    primaryColor = MaterialTheme.colorScheme.primary,
                                                    secondaryColor = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(60.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // About section
                item {
                    var aboutExpanded by remember { mutableStateOf(false) }
                    
                    SettingsSection(
                        title = "About",
                        icon = Icons.Rounded.Info,
                        isExpanded = aboutExpanded,
                        onExpandToggle = { aboutExpanded = !aboutExpanded }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // App icon placeholder
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = "App Icon",
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            // App name
                            Text(
                                text = "Musify Music Player",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            
                            // Version
                            Text(
                                text = "Version 2.0",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Divider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                            )
                            
                            // Organization
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Organization",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "Oscorp Techs",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            
                            // Developers
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Developers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "King Scorpion",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Prince Delex",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            
                            // Copyright
                            Text(
                                text = "© 2025 Oscorp Techs. All rights reserved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Color picker dialog
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = when (currentColorEditTarget) {
                "primary" -> customPrimaryColor
                "secondary" -> customSecondaryColor
                "background" -> customBackgroundColor
                else -> Color.White
            },
            onColorSelected = { color ->
                when (currentColorEditTarget) {
                    "primary" -> customPrimaryColor = color
                    "secondary" -> customSecondaryColor = color
                    "background" -> customBackgroundColor = color
                }
                showColorPicker = false
            },
            onDismiss = {
                showColorPicker = false
            }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                val rotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "rotation"
                )
                
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.graphicsLayer(rotationZ = rotation)
                )
            }
            
            // Expandable content
            AnimatedVisibility(visible = isExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ColorPresetSwatch(
    preset: DynamicColors,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable { onClick() }
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Three-color ring representation
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(preset.primary))
            Box(Modifier.weight(1f).fillMaxHeight().background(preset.secondary))
            Box(Modifier.weight(1f).fillMaxHeight().background(preset.surface))
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
fun ColorPickerRow(
    title: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .clickable { onClick() }
        )
    }
}

@Composable
fun DraggableLayoutItem(
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.DragIndicator,
            contentDescription = "Drag",
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var red by remember { mutableStateOf(initialColor.red) }
    var green by remember { mutableStateOf(initialColor.green) }
    var blue by remember { mutableStateOf(initialColor.blue) }
    
    val selectedColor = Color(red, green, blue)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Choose Color",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Color preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(selectedColor)
                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                )
                
                // RGB sliders
                ColorSlider(
                    value = red,
                    onValueChange = { red = it },
                    label = "Red",
                    thumbColor = Color.Red
                )
                
                ColorSlider(
                    value = green,
                    onValueChange = { green = it },
                    label = "Green",
                    thumbColor = Color.Green
                )
                
                ColorSlider(
                    value = blue,
                    onValueChange = { blue = it },
                    label = "Blue",
                    thumbColor = Color.Blue
                )
                
                // Hex code
                val hexCode = String.format("#%02X%02X%02X", 
                    (red * 255).toInt(), 
                    (green * 255).toInt(), 
                    (blue * 255).toInt()
                )
                
                Text(
                    text = "Hex: $hexCode",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorSelected(selectedColor) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ColorSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    thumbColor: Color
) {
    Column {
        Text(
            text = "$label: ${(value * 255).toInt()}",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = thumbColor.copy(alpha = 0.7f),
                inactiveTrackColor = thumbColor.copy(alpha = 0.3f)
            )
        )
    }
}
