package com.musify.mu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    tintTop: Color = Color.White.copy(alpha = 0.08f),
    tintBottom: Color = Color.Black.copy(alpha = 0.20f),
    blurRadius: Int = 24
) {
    // Simple glass effect: background gradient + blur + translucent tint
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f }
            .blur(blurRadius.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(tintTop, tintBottom)
                )
            )
    )
}
 