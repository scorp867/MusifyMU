package com.musify.mu.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.musify.mu.ui.theme.AppThemeManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated background component that reacts to music
 * Supports different animation styles: waves, particles, pulse
 */
@Composable
fun AnimatedBackground(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeManager = remember { AppThemeManager.getInstance(context) }
    
    // Only show animated background if enabled in settings
    if (!themeManager.useAnimatedBackgrounds) return
    
    val animationStyle = remember { themeManager.animationStyle }
    
    Box(modifier = modifier) {
        when (animationStyle) {
            "waves" -> WaveAnimation(isPlaying, primaryColor, secondaryColor)
            "particles" -> ParticleAnimation(isPlaying, primaryColor, secondaryColor)
            "pulse" -> PulseAnimation(isPlaying, primaryColor, secondaryColor)
            else -> WaveAnimation(isPlaying, primaryColor, secondaryColor)
        }
    }
}

@Composable
private fun WaveAnimation(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    
    // Animation speed depends on whether music is playing
    val speed = if (isPlaying) 1500 else 3000
    
    // Animate wave phase
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(speed, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    
    // Animate wave amplitude
    val amplitude by infiniteTransition.animateFloat(
        initialValue = if (isPlaying) 25f else 15f,
        targetValue = if (isPlaying) 40f else 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(speed, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "amplitude"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        // Draw multiple waves with different phases and colors
        drawWave(
            phase = phase,
            amplitude = amplitude,
            frequency = 0.02f,
            color = primaryColor.copy(alpha = 0.2f),
            width = width,
            height = height
        )
        
        drawWave(
            phase = phase + PI.toFloat() / 2,
            amplitude = amplitude * 0.7f,
            frequency = 0.03f,
            color = secondaryColor.copy(alpha = 0.15f),
            width = width,
            height = height
        )
        
        drawWave(
            phase = phase + PI.toFloat(),
            amplitude = amplitude * 0.5f,
            frequency = 0.04f,
            color = primaryColor.copy(alpha = 0.1f),
            width = width,
            height = height
        )
    }
}

private fun DrawScope.drawWave(
    phase: Float,
    amplitude: Float,
    frequency: Float,
    color: Color,
    width: Float,
    height: Float
) {
    val path = Path()
    val yCenter = height * 0.5f
    
    // Start from the left edge
    path.moveTo(0f, yCenter)
    
    // Draw the wave
    for (x in 0..width.toInt() step 5) {
        val y = yCenter + sin(x * frequency + phase) * amplitude
        path.lineTo(x.toFloat(), y)
    }
    
    // Complete the path by connecting to the bottom-right corner and back to start
    path.lineTo(width, height)
    path.lineTo(0f, height)
    path.close()
    
    // Draw the wave
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(
                color,
                color.copy(alpha = 0.1f)
            ),
            startY = yCenter - amplitude,
            endY = height
        )
    )
}

@Composable
private fun ParticleAnimation(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val particleCount = 20
    val maxRadius = with(density) { 8.dp.toPx() }
    
    // Generate random particles
    val particles = remember {
        List(particleCount) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * maxRadius,
                color = if (Random.nextBoolean()) primaryColor else secondaryColor,
                speedMultiplier = Random.nextFloat() * 2f + 0.5f
            )
        }
    }
    
    // Animation speed depends on whether music is playing
    val speed = if (isPlaying) 3000 else 6000
    
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(speed, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        particles.forEach { particle ->
            val x = (particle.x + progress * particle.speedMultiplier) % 1f * width
            val y = (particle.y + progress * particle.speedMultiplier * 0.5f) % 1f * height
            val alpha = if (isPlaying) 0.3f else 0.15f
            
            drawCircle(
                color = particle.color.copy(alpha = alpha),
                radius = particle.radius,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun PulseAnimation(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    // Animation speed depends on whether music is playing
    val speed = if (isPlaying) 800 else 2000
    
    // Animate pulse scale
    val scale by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(speed, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val center = Offset(width / 2, height / 2)
        
        // Draw multiple pulses with different colors
        for (i in 0 until 3) {
            val pulseScale = (scale + i * 0.33f) % 1f
            val radius = pulseScale * width * 0.8f
            val alpha = (1f - pulseScale) * (if (isPlaying) 0.3f else 0.15f)
            
            drawCircle(
                color = (if (i % 2 == 0) primaryColor else secondaryColor).copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
        }
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
    val speedMultiplier: Float
)
