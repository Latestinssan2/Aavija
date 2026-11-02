package com.preet.aavija

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 8: the transfer animation.
 *
 * A pulsing orb with orbiting particles, shown only while bytes are actually
 * moving. It is deliberately self-contained (no images, no third-party
 * animation library) and has no interaction: it cannot be tapped, so it can
 * never get in the way of the real buttons underneath.
 *
 * The "spring unfold" is the entrance: the whole overlay scales up from 70% on
 * a spring, then the orb keeps a slow spin and a heartbeat pulse while the
 * transfer runs.
 */
@Composable
fun TransferOrbOverlay(
    visible: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(160)) +
            scaleIn(
                initialScale = 0.70f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 260f),
            ),
        exit = fadeOut(animationSpec = tween(220)) +
            scaleOut(targetScale = 0.92f, animationSpec = tween(220)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            val motion = rememberInfiniteTransition(label = "transfer-orb")
            val spin by motion.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2_200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "spin",
            )
            val pulse by motion.animateFloat(
                initialValue = 0.94f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            )
            val burst by motion.animateFloat(
                initialValue = 0.15f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_300, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "burst",
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        },
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val orbit = size.minDimension * 0.28f +
                        size.minDimension * 0.14f * burst

                    // Particles fly outward and fade as the burst expands.
                    val particleAlpha = (1f - burst).coerceIn(0f, 1f) * 0.9f
                    repeat(14) { i ->
                        val angle = Math.toRadians(
                            i * (360.0 / 14.0) + spin.toDouble(),
                        )
                        val point = Offset(
                            center.x + cos(angle).toFloat() * orbit,
                            center.y + sin(angle).toFloat() * orbit,
                        )
                        drawCircle(
                            color = Color(0xFF80D8FF).copy(alpha = particleAlpha),
                            radius = size.minDimension * (0.009f + 0.009f * (1f - burst)),
                            center = point,
                        )
                    }

                    // The orb: a rotating spectrum ring around a bright core.
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF29B6F6),
                                Color(0xFF7C4DFF),
                                Color(0xFF26C6DA),
                                Color(0xFF29B6F6),
                            ),
                        ),
                        radius = size.minDimension * 0.18f,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.92f),
                        radius = size.minDimension * 0.10f,
                        center = center,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    label,
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
