package com.aartiq.aavija

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Animated Splash Screen for Aavija.
 *
 * Features:
 * - Dynamic system theme based app logo background (Dark obsidian vs Light snow).
 * - Multi-stage rhythmic pulsing animation with expanding aura ripple waves.
 * - Rotating iridescent specular halo border.
 * - Bold Gujarati ("આવી જા") and English brand typography.
 * - Smooth auto-transition after 1.8 seconds or instant skip on tap.
 */
@Composable
fun AavijaSplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark

    // Back button dismisses splash and enters app
    BackHandler {
        onDismiss()
    }

    // Auto-dismiss after 1.8s
    LaunchedEffect(Unit) {
        delay(1800L)
        onDismiss()
    }

    // Infinite transitions for pulsing animations
    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulsing")

    // Breathing scale for the main app icon
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "icon_scale",
    )

    // Pulsing aura glow alpha
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_alpha",
    )

    // Ripple wave 1: expanding outward
    val ripple1 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple1",
    )

    // Ripple wave 2: delayed phase
    val ripple2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, delayMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple2",
    )

    // Slow iridescent rotation around icon border
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "border_rotation",
    )

    // Live status dot pulse
    val liveDotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live_dot",
    )

    // System-theme based colors
    val logoBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val logoDrawable = if (isDark) R.drawable.app_logo_bright else R.drawable.app_logo
    val auraColor = if (isDark) Color(0xFF00E5FF) else Color(0xFF0284C7)
    val secondaryAura = if (isDark) Color(0xFF8B5CF6) else Color(0xFF6366F1)

    val backgroundBrush = if (isDark) {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF090D16),
                Color(0xFF030508),
            ),
            radius = 1600f,
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFFF0F7FF),
                Color(0xFFF8FAFC),
                Color(0xFFEEF2F6),
            ),
            radius = 1600f,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .pointerInput(Unit) {
                detectTapGestures {
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Multi-stage Canvas Ripple Aura Waves
        Canvas(
            modifier = Modifier
                .size(380.dp),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 60.dp.toPx()

            // Outer Ripple 1
            val progress1 = ((ripple1 - 0.8f) / 1.4f).coerceIn(0f, 1f)
            val alpha1 = (1f - progress1) * 0.42f
            drawCircle(
                color = auraColor.copy(alpha = alpha1),
                radius = baseRadius * ripple1,
                center = center,
            )

            // Outer Ripple 2
            val progress2 = ((ripple2 - 0.8f) / 1.4f).coerceIn(0f, 1f)
            val alpha2 = (1f - progress2) * 0.32f
            drawCircle(
                color = secondaryAura.copy(alpha = alpha2),
                radius = baseRadius * ripple2,
                center = center,
            )

            // Core breathing aura glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        auraColor.copy(alpha = 0.45f * glowAlpha),
                        secondaryAura.copy(alpha = 0.20f * glowAlpha),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = baseRadius * 1.5f * iconScale,
                ),
                radius = baseRadius * 1.5f * iconScale,
                center = center,
            )
        }

        // Central Content Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            // Pulsing App Icon Card
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .scale(iconScale),
                contentAlignment = Alignment.Center,
            ) {
                // Rotating Iridescent Glow Ring (Dark theme) or Crisp Glow (Light theme)
                if (isDark) {
                    Box(
                        modifier = Modifier
                            .size(126.dp)
                            .rotate(rotationAngle)
                            .clip(RoundedCornerShape(36.dp))
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFF00E5FF),
                                        Color(0xFF8B5CF6),
                                        Color(0xFFEC4899),
                                        Color(0xFF00E5FF),
                                    )
                                )
                            ),
                    )
                }

                // System theme based App Logo Card
                Surface(
                    modifier = Modifier
                        .size(114.dp)
                        .shadow(
                            elevation = if (isDark) 24.dp else 12.dp,
                            shape = RoundedCornerShape(32.dp),
                            ambientColor = auraColor.copy(alpha = 0.4f),
                            spotColor = secondaryAura.copy(alpha = 0.5f),
                        ),
                    shape = RoundedCornerShape(32.dp),
                    color = logoBg,
                    border = if (isDark) {
                        BorderStroke(1.5.dp, Color.White.copy(alpha = 0.18f))
                    } else {
                        BorderStroke(1.5.dp, auraColor.copy(alpha = 0.35f))
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = logoDrawable),
                            contentDescription = "Aavija Pulsing App Logo",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            // Main Brand Title ("આવી જા")
            Text(
                text = "આવી જા",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                letterSpacing = (-0.5).sp,
            )

            Spacer(Modifier.height(4.dp))

            // English Subtitle
            Text(
                text = "AAVIJA",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = auraColor,
                letterSpacing = 4.sp,
            )

            Spacer(Modifier.height(18.dp))

            // Tagline Pill with Live Pulsing Dot
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF131B2E) else Color(0xFFFFFFFF),
                border = BorderStroke(
                    1.dp,
                    if (isDark) Color(0x3300E5FF) else Color(0x330284C7),
                ),
                shadowElevation = if (isDark) 0.dp else 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF).copy(alpha = liveDotAlpha)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Gesture AirDrop • Wi-Fi",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // Bottom Footer: Creator credit & Security
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Crafted by",
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Preet Patel",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(5.dp))
                // Verified checkmark icon
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF00C853),
                    modifier = Modifier.size(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.size(7.dp)) {
                            // Mini white checkmark
                            drawLine(
                                color = Color.White,
                                start = Offset(1.5f, 3.5f),
                                end = Offset(3.5f, 5.5f),
                                strokeWidth = 1.5f,
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(3.5f, 5.5f),
                                end = Offset(6.5f, 1.5f),
                                strokeWidth = 1.5f,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "100% Native • TLS 1.3 • Zero Cloud",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textTertiary,
                letterSpacing = 0.5.sp,
            )
        }
    }
}
