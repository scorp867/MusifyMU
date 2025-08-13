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
        targetValue = (scrollOffset / 120f).coerceIn(0f, 0.9f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "backgroundAlpha"
    )

    val elevationAlpha by animateFloatAsState(
        targetValue = (scrollOffset / 120f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "elevationAlpha"
    )

    // Create modern gradient background
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha),
            MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha * 0.7f)
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                topBarHeight = with(density) { coordinates.size.height.toDp() }
            },
        color = Color.Transparent,
        shadowElevation = (4.dp * elevationAlpha)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundGradient)
        ) {
            // Compact top bar content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(44.dp), // Reduced from 56dp
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compact menu button
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.size(36.dp) // Reduced from 40dp
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Title with better typography
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - (backgroundAlpha * 0.2f)
                        }
                    )
                }

                // Compact action buttons
                IconButton(
                    onClick = onSearch,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Additional actions if provided
                actions()
            }
        }
    }
}

