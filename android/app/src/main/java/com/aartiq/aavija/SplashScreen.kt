package com.aartiq.aavija

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Pure Minimalist & Mesmerizing Splash Screen.
 *
 * Design:
 * - ZERO text on screen. 100% focus on the pure Aavija brand logo.
 * - Hypnotic, organic breathing physics (scale & luminescence).
 * - Multi-layer concentric gesture soundwave ripples that dissolve into infinity.
 * - Pure adaptive background: True Obsidian Black (#000000) vs Pure Porcelain White (#FFFFFF).
 */
@Composable
fun AavijaSplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark

    BackHandler {
        onDismiss()
    }

    // Auto-dismiss smoothly after 1.8 seconds
    LaunchedEffect(Unit) {
        delay(1800L)
        onDismiss()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mesmerizing_splash")

    // Hypnotic organic breathing scale for the hero logo
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo_scale",
    )

    // Breathing radial ambient aura
    val auraBloom by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aura_bloom",
    )

    // Ripple Wave 1 (smooth gesture wave expanding outward)
    val ripple1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple1",
    )

    // Ripple Wave 2 (delayed phase for rhythmic continuity)
    val ripple2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple2",
    )

    // Ripple Wave 3 (ambient slow wave)
    val ripple3 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple3",
    )

    // System-theme based graphics & palettes
    val logoDrawable = if (isDark) R.drawable.app_logo_hero_bright else R.drawable.app_logo_hero
    val primaryCyan = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    val ambientIndigo = if (isDark) Color(0xFF8B5CF6) else Color(0xFF6366F1)

    // True infinite solid background for highest contrast and purity
    val backgroundColor = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures {
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Hypnotic Ripple Soundwaves and Ambient Radial Bloom
        Canvas(
            modifier = Modifier.size(460.dp),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 85.dp.toPx()

            // Concentric Ripple Wave 1
            val p1 = ((ripple1 - 0.5f) / 2.1f).coerceIn(0f, 1f)
            val alpha1 = (1f - p1) * (if (isDark) 0.32f else 0.20f)
            drawCircle(
                color = primaryCyan.copy(alpha = alpha1),
                radius = baseRadius * ripple1,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // Concentric Ripple Wave 2
            val p2 = ((ripple2 - 0.5f) / 2.1f).coerceIn(0f, 1f)
            val alpha2 = (1f - p2) * (if (isDark) 0.26f else 0.16f)
            drawCircle(
                color = ambientIndigo.copy(alpha = alpha2),
                radius = baseRadius * ripple2,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // Concentric Ripple Wave 3
            val p3 = ((ripple3 - 0.5f) / 2.1f).coerceIn(0f, 1f)
            val alpha3 = (1f - p3) * (if (isDark) 0.20f else 0.12f)
            drawCircle(
                color = primaryCyan.copy(alpha = alpha3),
                radius = baseRadius * ripple3,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // Soft Ethereal Core Glow (Breathing with the logo)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryCyan.copy(alpha = (if (isDark) 0.42f else 0.22f) * auraBloom),
                        ambientIndigo.copy(alpha = (if (isDark) 0.20f else 0.10f) * auraBloom),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = baseRadius * 1.9f * logoScale,
                ),
                radius = baseRadius * 1.9f * logoScale,
                center = center,
            )
        }

        // Pure Borderless Hero Logo (Centered, Hypnotic, Zero Distractions)
        Box(
            modifier = Modifier
                .size(width = 240.dp, height = 192.dp)
                .scale(logoScale),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = logoDrawable),
                contentDescription = "Aavija",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
