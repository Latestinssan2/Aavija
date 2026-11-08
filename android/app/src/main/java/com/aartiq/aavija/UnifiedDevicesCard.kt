package com.aartiq.aavija

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TargetPlatform {
    MAC,
    WINDOWS,
    ANDROID,
}

data class UnifiedPeer(
    val id: String,
    val name: String,
    val platform: TargetPlatform,
    val isPaired: Boolean,
    val isActiveTarget: Boolean,
    val isReachable: Boolean = true,
    val onSelect: () -> Unit,
    val onUnpair: (() -> Unit)? = null,
)

/**
 * Unified Single Device Panel for Aavija.
 *
 * Shows all discovered macOS, Windows, and Android peers in a single
 * glassmorphic radar card, replacing disparate platform cards.
 */
@Composable
fun UnifiedDevicesCard(
    devices: List<UnifiedPeer>,
    isAdvertising: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark

    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val radarSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar_angle",
    )

    val radarPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_pulse",
    )

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.surfaceBorder),
        shadowElevation = if (isDark) 0.dp else 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (isDark) 0.dp else 12.dp, RoundedCornerShape(24.dp)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header Row: Title, Radar Animation & Device Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsing Radar Indicator
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            drawCircle(
                                color = Color(0xFF00E5FF).copy(alpha = 0.25f * radarPulse),
                                radius = size.minDimension / 2f,
                            )
                            drawCircle(
                                color = Color(0xFF00E5FF),
                                radius = 4.dp.toPx(),
                                center = center,
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "Nearby Devices",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Single Wi-Fi Peer Radar",
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                        )
                    }
                }

                // Device Count Badge
                Surface(
                    shape = CircleShape,
                    color = if (devices.isNotEmpty()) Color(0xFF00E5FF).copy(alpha = 0.15f) else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)),
                    border = BorderStroke(
                        1.dp,
                        if (devices.isNotEmpty()) Color(0xFF00E5FF).copy(alpha = 0.35f) else colors.surfaceBorder,
                    ),
                ) {
                    Text(
                        text = if (devices.isNotEmpty()) "${devices.size} found" else "Scanning...",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (devices.isNotEmpty()) Color(0xFF00E5FF) else colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (devices.isEmpty()) {
                // Empty State: Animated Wi-Fi Radar Scanner
                RadarScanningEmptyState(isDark = isDark, radarPulse = radarPulse)
            } else {
                // Unified Device Rows
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { device ->
                        UnifiedDeviceRow(device = device, isDark = isDark)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedDeviceRow(
    device: UnifiedPeer,
    isDark: Boolean,
) {
    val colors = LocalAavijaColors.current

    val platformIcon = when (device.platform) {
        TargetPlatform.MAC -> "🍎"
        TargetPlatform.WINDOWS -> "🪟"
        TargetPlatform.ANDROID -> "📱"
    }

    val platformLabel = when (device.platform) {
        TargetPlatform.MAC -> "macOS"
        TargetPlatform.WINDOWS -> "Windows"
        TargetPlatform.ANDROID -> "Android"
    }

    val isHighlight = device.isActiveTarget

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isHighlight) {
            Color(0xFF00E5FF).copy(alpha = if (isDark) 0.12f else 0.08f)
        } else {
            if (isDark) Color(0xFF111728) else Color(0xFFF8FAFC)
        },
        border = BorderStroke(
            1.dp,
            if (isHighlight) {
                Color(0xFF00E5FF).copy(alpha = 0.5f)
            } else {
                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = device.onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // Platform Icon Container
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when (device.platform) {
                        TargetPlatform.MAC -> if (isDark) Color(0xFF1E283D) else Color(0xFFE2E8F0)
                        TargetPlatform.WINDOWS -> if (isDark) Color(0xFF0E2A3B) else Color(0xFFE0F2FE)
                        TargetPlatform.ANDROID -> if (isDark) Color(0xFF0F2E22) else Color(0xFFDCFCE7)
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(platformIcon, fontSize = 20.sp)
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        if (device.isPaired) {
                            Spacer(Modifier.width(6.dp))
                            Text("🔒", fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = platformLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (device.platform) {
                                TargetPlatform.MAC -> Color(0xFF38BDF8)
                                TargetPlatform.WINDOWS -> Color(0xFF0284C7)
                                TargetPlatform.ANDROID -> Color(0xFF10B981)
                            },
                        )
                        Text(
                            text = " • ",
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                        )
                        Text(
                            text = when {
                                device.isActiveTarget -> "Active Send Target"
                                device.isPaired -> "Paired & Ready"
                                else -> "Tap to Connect"
                            },
                            fontSize = 11.sp,
                            color = if (device.isActiveTarget) Color(0xFF00E5FF) else colors.textSecondary,
                        )
                    }
                }
            }

            // Right Action / Status Badges
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device.onUnpair != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clickable { device.onUnpair.invoke() },
                    ) {
                        Text(
                            text = "Unpair ✕",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        device.isActiveTarget -> Color(0xFF00E5FF)
                        device.isPaired -> Color(0xFF10B981).copy(alpha = 0.15f)
                        else -> if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            device.isActiveTarget -> Color(0xFF00E5FF)
                            device.isPaired -> Color(0xFF10B981).copy(alpha = 0.4f)
                            else -> colors.surfaceBorder
                        },
                    ),
                ) {
                    Text(
                        text = when {
                            device.isActiveTarget -> "Active ⚡"
                            device.isPaired -> "Paired ✓"
                            else -> "Pair →"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            device.isActiveTarget -> Color(0xFF060911)
                            device.isPaired -> Color(0xFF10B981)
                            else -> colors.textSecondary
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarScanningEmptyState(
    isDark: Boolean,
    radarPulse: Float,
) {
    val colors = LocalAavijaColors.current

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isDark) Color(0xFF0D1424) else Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.15f * radarPulse),
                        radius = 38.dp.toPx() * radarPulse,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.08f),
                        radius = 28.dp.toPx(),
                    )
                }
                Text("📡", fontSize = 28.sp)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Scanning Local Wi-Fi...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Open Aavija on your Mac, Windows PC, or another Android phone on the same Wi-Fi network.",
                fontSize = 11.5.sp,
                color = colors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 16.sp,
            )
        }
    }
}
