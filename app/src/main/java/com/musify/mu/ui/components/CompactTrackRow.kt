package com.musify.mu.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

@Composable
fun CompactTrackRow(
    title: String,
    subtitle: String,
    artData: Any?,
    mediaUri: String? = null,
    contentDescription: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    showIndicator: Boolean = isPlaying,
    useGlass: Boolean = true,
    extraArtOverlay: (@Composable BoxScope.() -> Unit)? = null
) {
    val pressed by remember { MutableInteractionSource() }.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = Spring.StiffnessLow))
    val shape = RoundedCornerShape(14.dp)
    val containerColor = if (useGlass) MaterialTheme.colorScheme.surface.copy(alpha = 0.35f) else Color.Transparent
    val borderStroke = if (useGlass) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) else null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        color = containerColor,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = borderStroke
    ) {
        val innerMod = Modifier
            .then(
                if (useGlass) Modifier
                    .clip(shape)
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.02f)
                            )
                        )
                    ) else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 2.dp)
        Row(
            modifier = innerMod
                .height(60.dp)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(
                data = artData,
                mediaUri = mediaUri,
                albumId = null,
                contentDescription = contentDescription,
                modifier = Modifier.size(52.dp),
                enableOnDemand = true,
                cacheKey = mediaUri // Add stable cache key to prevent re-loading
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showIndicator) {
                        PlayingIndicator(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                        )
                    }
                    if (extraArtOverlay != null) {
                        extraArtOverlay()
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            if (trailingContent != null) {
                Spacer(Modifier.width(6.dp))
                trailingContent()
            }
        }
    }
}


