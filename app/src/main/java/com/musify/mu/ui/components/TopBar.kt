package com.musify.mu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    scrollOffset: Float = 0f,
    onSearch: () -> Unit = {},
    onMenu: () -> Unit = {},
    onSettings: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    var topBarHeight by remember { mutableStateOf(0.dp) }
    
    // Calculate animated properties based on scroll
    val backgroundAlpha by animateFloatAsState(
        targetValue = (scrollOffset / 150f).coerceIn(0f, 0.85f), 
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "backgroundAlpha"
    )
    
    val elevationAlpha by animateFloatAsState(
        targetValue = (scrollOffset / 150f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "elevationAlpha"
    )
    
    // Create gradient background
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha),
            MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha * 0.8f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                topBarHeight = with(density) { coordinates.size.height.toDp() }
            }
    ) {
        // Background with blur effect
        if (scrollOffset > 5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topBarHeight)
                    .background(backgroundGradient)
                    .zIndex(0f)
                    .graphicsLayer {
                        shadowElevation = 8f * elevationAlpha
                        alpha = backgroundAlpha
                    }
                    .blur(radius = 1.dp)
            )
        }
        
        // Actual top bar content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Menu button
            IconButton(
                onClick = onMenu,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Menu",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Title with animated weight
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - (backgroundAlpha * 0.3f)
                    }
                )
            }
            
            // Search button
            IconButton(
                onClick = onSearch,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Settings button
            IconButton(
                onClick = onSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Additional actions if provided
            actions()
        }
    }
}

