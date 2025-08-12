package com.musify.mu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.musify.mu.ui.navigation.Screen
import com.musify.mu.ui.theme.DeepPurple
import com.musify.mu.ui.theme.ElectricBlue
import com.musify.mu.ui.theme.NeonBlue

data class BottomNavItem(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector
)

private val items = listOf(
    BottomNavItem(Screen.Home.route, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    BottomNavItem(Screen.Library.route, "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic)
)

@Composable
fun BottomBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Create gradient background for the navigation bar
    val navigationBarGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        )
    )

    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 8.dp
    ) {
        items.forEachIndexed { index, item ->
            val selected = currentRoute == item.route
            val animatedWeight by animateFloatAsState(
                targetValue = if (selected) 1.5f else 1f,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "weight"
            )
            
            Box(
                modifier = Modifier
                    .weight(animatedWeight)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectable(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Screen.Library.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Indicator dot at top when selected
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    
                    // Icon - changes between filled and outlined based on selection
                    Icon(
                        imageVector = if (selected) item.filledIcon else item.outlinedIcon,
                        contentDescription = item.label,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    // Animated label visibility
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn(animationSpec = tween(150)) + expandVertically(),
                        exit = fadeOut(animationSpec = tween(150)) + shrinkVertically()
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

