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
 * Animated background component that reacts to music.
 * Supports styles: waves, particles, pulse, neon_grid, orbit_rings.
 * All animations pause when not playing and resume from the last state.
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
            "neon_grid" -> NeonGridAnimation(isPlaying, primaryColor, secondaryColor)
            "orbit_rings" -> OrbitRingsAnimation(isPlaying, primaryColor, secondaryColor)
            else -> WaveAnimation(isPlaying, primaryColor, secondaryColor)
        }
    }
}

@Composable
fun AnimatedBackgroundPreview(
    styleKey: String,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (styleKey) {
            "waves" -> WaveAnimation(isPlaying, primaryColor, secondaryColor)
            "particles" -> ParticleAnimation(isPlaying, primaryColor, secondaryColor)
            "pulse" -> PulseAnimation(isPlaying, primaryColor, secondaryColor)
            "neon_grid" -> NeonGridAnimation(isPlaying, primaryColor, secondaryColor)
            "orbit_rings" -> OrbitRingsAnimation(isPlaying, primaryColor, secondaryColor)
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
    var phase by remember { mutableStateOf(0f) }
    var amplitude by remember { mutableStateOf(25f) }
    var ampIncreasing by remember { mutableStateOf(true) }
    val playing by rememberUpdatedState(isPlaying)

    LaunchedEffect(Unit) {
        while (true) {
            if (playing) {
                // Advance phase and amplitude without resetting when paused
                phase = (phase + 0.08f) % (2 * PI.toFloat())
                val minAmp = 15f
                val maxAmp = 40f
                val delta = 0.6f
                amplitude = (if (ampIncreasing) amplitude + delta else amplitude - delta)
                    .coerceIn(minAmp, maxAmp)
                if (amplitude <= minAmp + 0.1f) ampIncreasing = true
                if (amplitude >= maxAmp - 0.1f) ampIncreasing = false
            }
            // ~60 FPS when playing, slower idle otherwise
            kotlinx.coroutines.delay(if (playing) 16L else 50L)
        }
    }
    
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
    
    var progress by remember { mutableStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        while (true) {
            if (playing) {
                progress = (progress + 0.004f) % 1f
            }
            kotlinx.coroutines.delay(if (playing) 16L else 50L)
        }
    }
    
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
    var scale by remember { mutableStateOf(0f) }
    var forward by remember { mutableStateOf(true) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        while (true) {
            if (playing) {
                val delta = 0.02f
                scale = (if (forward) scale + delta else scale - delta).coerceIn(0f, 1f)
                if (scale <= 0.01f) forward = true
                if (scale >= 0.99f) forward = false
            }
            kotlinx.coroutines.delay(if (playing) 16L else 50L)
        }
    }
    
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

@Composable
private fun NeonGridAnimation(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    var t by remember { mutableStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        while (true) {
            if (playing) t = (t + 1f) % 10000f
            kotlinx.coroutines.delay(if (playing) 16L else 50L)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cell = (minOf(w, h) / 10f).coerceAtLeast(40f)
        val offset = (t % cell)
        val base = primaryColor.copy(alpha = 0.10f)
        val glow = secondaryColor.copy(alpha = 0.18f)

        // Vertical lines
        var x = -cell + offset
        while (x < w + cell) {
            drawLine(
                color = base,
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1.5f
            )
            // subtle glow
            drawLine(
                color = glow,
                start = Offset(x + 2f, 0f),
                end = Offset(x + 2f, h),
                strokeWidth = 1f
            )
            x += cell
        }

        // Horizontal lines
        var y = -cell + offset
        while (y < h + cell) {
            drawLine(
                color = base,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.5f
            )
            drawLine(
                color = glow,
                start = Offset(0f, y + 2f),
                end = Offset(w, y + 2f),
                strokeWidth = 1f
            )
            y += cell
        }
    }
}

@Composable
private fun OrbitRingsAnimation(
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    var t by remember { mutableStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        while (true) {
            if (playing) t += 0.02f
            kotlinx.coroutines.delay(if (playing) 16L else 50L)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2f, h / 2f)
        val numRings = 4
        val maxR = minOf(w, h) * 0.45f
        val ringStroke = 2.dp.toPx()

        for (i in 0 until numRings) {
            val r = maxR * (0.35f + i / (numRings.toFloat()))
            val color = if (i % 2 == 0) primaryColor.copy(alpha = 0.18f) else secondaryColor.copy(alpha = 0.18f)
            // ring
            drawCircle(color = color, radius = r, center = c, style = Stroke(width = ringStroke))

            // moving node along the ring
            val angle = t * (0.8f + 0.2f * i) + i * 1.2f
            val px = c.x + r * kotlin.math.cos(angle)
            val py = c.y + r * kotlin.math.sin(angle)
            drawCircle(
                color = color.copy(alpha = 0.5f),
                radius = 4.dp.toPx(),
                center = Offset(px, py)
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
