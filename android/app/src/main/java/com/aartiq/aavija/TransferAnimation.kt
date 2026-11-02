package com.aartiq.aavija

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 8: Premium particle transfer animation.
 *
 * A glowing multi-layered quantum orb with orbital particles and pulse waves,
 * displayed while files or screenshots are actively streaming over Wi-Fi.
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
        enter = fadeIn(animationSpec = tween(220)) +
            scaleIn(
                initialScale = 0.65f,
                animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
            ),
        exit = fadeOut(animationSpec = tween(260)) +
            scaleOut(targetScale = 0.85f, animationSpec = tween(260)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            val motion = rememberInfiniteTransition(label = "transfer-quantum-orb")

            val spinA by motion.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "spin-a",
            )
            val spinB by motion.animateFloat(
                initialValue = 360f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2_400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "spin-b",
            )
            val pulse by motion.animateFloat(
                initialValue = 0.92f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse-core",
            )
            val ripple by motion.animateFloat(
                initialValue = 0.1f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "ripple",
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Dynamic Glowing Orb Canvas
                Box(
                    modifier = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                            },
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val minDim = size.minDimension

                        // Expanding energy wave rings
                        val waveRadius = minDim * (0.22f + 0.28f * ripple)
                        val waveAlpha = (1f - ripple).coerceIn(0f, 1f) * 0.5f
                        drawCircle(
                            color = Color(0xFF00E5FF).copy(alpha = waveAlpha),
                            radius = waveRadius,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
                        )

                        // Ambient halo bloom
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.35f),
                                    Color(0xFF7C4DFF).copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = minDim * 0.45f,
                            ),
                            radius = minDim * 0.45f,
                            center = center,
                        )

                        // Outer orbital particle ring (clockwise)
                        val orbitA = minDim * 0.36f
                        repeat(16) { i ->
                            val angle = Math.toRadians(i * (360.0 / 16.0) + spinA.toDouble())
                            val point = Offset(
                                center.x + cos(angle).toFloat() * orbitA,
                                center.y + sin(angle).toFloat() * orbitA,
                            )
                            drawCircle(
                                color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                                radius = 3.5f,
                                center = point,
                            )
                        }

                        // Inner orbital particle ring (counter-clockwise)
                        val orbitB = minDim * 0.26f
                        repeat(10) { i ->
                            val angle = Math.toRadians(i * (360.0 / 10.0) + spinB.toDouble())
                            val point = Offset(
                                center.x + cos(angle).toFloat() * orbitB,
                                center.y + sin(angle).toFloat() * orbitB,
                            )
                            drawCircle(
                                color = Color(0xFFC084FC).copy(alpha = 0.90f),
                                radius = 2.5f,
                                center = point,
                            )
                        }

                        // Central living quantum core
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF818CF8),
                                    Color(0xFFA855F7),
                                    Color(0xFF00E5FF),
                                ),
                                center = center,
                            ),
                            radius = minDim * 0.16f,
                            center = center,
                        )
                        // Core center hot spot
                        drawCircle(
                            color = Color.White.copy(alpha = 0.95f),
                            radius = minDim * 0.07f,
                            center = center,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Gujarati Animated Heading: "આવતું છે"
                val textShimmer by motion.animateFloat(
                    initialValue = -0.5f,
                    targetValue = 1.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2_200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "text-shimmer",
                )

                Text(
                    text = "આવતું છે",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF00E5FF),
                                Color.White,
                                Color(0xFFC084FC),
                                Color(0xFF00E5FF),
                            ),
                            start = Offset(100f * textShimmer, 0f),
                            end = Offset(300f * (textShimmer + 1f), 100f),
                        )
                    ),
                    modifier = Modifier.graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                )

                Spacer(Modifier.height(10.dp))

                // Status glass pill
                Surface(
                    color = Color(0xFF131A29).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .shadow(16.dp, RoundedCornerShape(20.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Streaming bytes securely over Wi-Fi",
                            color = Color(0xFF00E5FF).copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
