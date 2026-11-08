package com.aartiq.aavija

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern Glassmorphic Startup & Onboarding Guide for Aavija.
 *
 * Walks users through:
 * 1. Mid-air gesture sharing (Grab ✊ & Release 🖐).
 * 2. Instant local Wi-Fi pairing (6-digit PIN, TLS 1.3).
 * 3. Installation guide across macOS, Windows, and Android.
 * 4. Pro tips (same Wi-Fi, VPNs, Magic Blackout mode).
 */
@Composable
fun StartupGuideSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark

    var currentPage by remember { mutableIntStateOf(0) }
    val totalPages = 4

    val glassBg = if (isDark) {
        Color(0xEE090D1A)
    } else {
        Color(0xF2FFFFFF)
    }

    val glassBorder = if (isDark) {
        Color.White.copy(alpha = 0.15f)
    } else {
        Color(0x330284C7)
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxSize(0.85f)
                .clickable(enabled = false) {} // prevent dismissing when tapping card
                .shadow(32.dp, RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            color = glassBg,
            border = BorderStroke(1.2.dp, glassBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -30 && currentPage < totalPages - 1) {
                                currentPage++
                            } else if (dragAmount > 30 && currentPage > 0) {
                                currentPage--
                            }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top Header Row: Title & Skip Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("✨", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Aavija Guide",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    Text(
                        text = "Skip",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                // Dynamic Animated Slide Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = currentPage,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { it } + fadeIn()).togetherWith(
                                    slideOutHorizontally { -it } + fadeOut()
                                )
                            } else {
                                (slideInHorizontally { -it } + fadeIn()).togetherWith(
                                    slideOutHorizontally { it } + fadeOut()
                                )
                            }
                        },
                        label = "guide_slide",
                    ) { page ->
                        when (page) {
                            0 -> GuideSlideGesture(isDark = isDark)
                            1 -> GuideSlidePairing(isDark = isDark)
                            2 -> GuideSlidePlatforms(isDark = isDark)
                            3 -> GuideSlideTips(isDark = isDark)
                        }
                    }
                }

                // Bottom Controls: Page Dots & Next/Done Button
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Dot Indicators
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 20.dp),
                    ) {
                        repeat(totalPages) { index ->
                            val isSelected = index == currentPage
                            val dotWidth = if (isSelected) 24.dp else 8.dp
                            val dotColor = if (isSelected) {
                                Color(0xFF00E5FF)
                            } else {
                                if (isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f)
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .height(8.dp)
                                    .width(dotWidth)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .clickable { currentPage = index },
                            )
                        }
                    }

                    // Navigation Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (currentPage > 0) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000),
                                border = BorderStroke(1.dp, glassBorder),
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable { currentPage-- },
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Back",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        Button(
                            onClick = {
                                if (currentPage < totalPages - 1) {
                                    currentPage++
                                } else {
                                    onDismiss()
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color(0xFF060911),
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF00E5FF)),
                        ) {
                            Text(
                                text = if (currentPage == totalPages - 1) "Got It! Let's Go 🚀" else "Next →",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Slide 1: Mid-Air Gesture Sharing Animation */
@Composable
private fun GuideSlideGesture(isDark: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "gesture_anim")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val waveRadius by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = 130f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_radius",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        // Glowing Gesture Aura Orb
        Box(
            modifier = Modifier
                .size(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val progress = ((waveRadius - 40f) / 90f).coerceIn(0f, 1f)
                val alpha = (1f - progress) * 0.4f

                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = alpha),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00E5FF).copy(alpha = 0.25f),
                            Color(0xFF8B5CF6).copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = 80.dp.toPx(),
                    ),
                    center = center,
                    radius = 80.dp.toPx(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.scale(pulseScale),
            ) {
                // Grab Hand (SVG 372329)
                Surface(
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF0D1424) else Color(0xFFFFFFFF),
                    border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(16.dp, CircleShape, spotColor = Color(0xFF00E5FF)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_hand_grab),
                            contentDescription = "Grab Fist",
                            colorFilter = ColorFilter.tint(Color(0xFF00E5FF)),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Transfer Pulse Arrow
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("➔", fontSize = 20.sp, color = Color(0xFF00E5FF))
                }

                // Release Hand (SVG 437006)
                Surface(
                    shape = CircleShape,
                    color = if (isDark) Color(0xFF0D1424) else Color(0xFFFFFFFF),
                    border = BorderStroke(1.5.dp, Color(0xFF10B981).copy(alpha = 0.6f)),
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(16.dp, CircleShape, spotColor = Color(0xFF10B981)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_hand_raised),
                            contentDescription = "Release Hand",
                            colorFilter = ColorFilter.tint(Color(0xFF10B981)),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Mid-Air Gesture Magic",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isDark) Color.White else Color(0xFF0F172A),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Close your fist (✊) in front of your camera to GRAB a file or screenshot. Open your palm (🖐) on the receiving device within 10 seconds to CATCH it in mid-air!",
            fontSize = 13.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
    }
}

/** Slide 2: Peer-to-Peer Wi-Fi Pairing Animation */
@Composable
private fun GuideSlidePairing(isDark: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        // Visual Pair Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Laptop Device (MacBook Pro)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF131D33) else Color(0xFFF1F5F9),
                border = BorderStroke(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                modifier = Modifier
                    .size(width = 92.dp, height = 80.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = Color(0xFF00E5FF)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(8.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.guide_laptop),
                        contentDescription = "MacBook Pro",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.width(56.dp).height(2.dp)) {
                    drawLine(
                        color = Color(0xFF00E5FF),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF00E5FF),
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🔒", fontSize = 11.sp)
                    }
                }
            }

            // Phone Device
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isDark) Color(0xFF131D33) else Color(0xFFF1F5F9),
                border = BorderStroke(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = Color(0xFF00E5FF)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(10.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.guide_phone),
                        contentDescription = "Smartphone",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = "Instant Peer-to-Peer Trust",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isDark) Color.White else Color(0xFF0F172A),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "No passwords or user accounts. Connect with a one-time 6-digit challenge PIN. Each device cryptographically pins the other's TLS 1.3 certificate for lifetime secure transfers.",
            fontSize = 13.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
    }
}

/** Slide 3: Cross-Platform Installation Guide (Mac, Windows, Android) */
@Composable
private fun GuideSlidePlatforms(isDark: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            text = "Install & Connect Across OS",
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isDark) Color.White else Color(0xFF0F172A),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))

        // Platform 1: macOS (Official Apple Logo)
        PlatformInstallItem(
            iconRes = R.drawable.ic_apple_logo_clean,
            tint = if (isDark) Color.White else Color.Black,
            title = "macOS (Apple Silicon & Intel)",
            desc = "Download .dmg or .app. Lives in your menu bar as 'આવી જા'. Grant Camera & Screen Capture permissions.",
            isDark = isDark,
        )

        Spacer(Modifier.height(10.dp))

        // Platform 2: Windows (Official Windows 11 Logo)
        PlatformInstallItem(
            iconRes = R.drawable.ic_windows_logo,
            tint = null,
            title = "Windows 10 / 11",
            desc = "Run Aavija for Windows. Background tray client with automatic Wi-Fi mDNS peer discovery.",
            isDark = isDark,
        )

        Spacer(Modifier.height(10.dp))

        // Platform 3: Android (Official Android 3D Bugdroid Logo)
        PlatformInstallItem(
            iconRes = R.drawable.ic_android_logo,
            tint = null,
            title = "Android (Phones & Tablets)",
            desc = "Jetpack Compose client with Magic Blackout OLED mode & full-screen pinch-to-zoom photo viewer.",
            isDark = isDark,
        )
    }
}

@Composable
private fun PlatformInstallItem(
    iconRes: Int,
    tint: Color?,
    title: String,
    desc: String,
    isDark: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isDark) Color(0xFF111728) else Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isDark) Color(0xFF192238) else Color(0xFFE2E8F0),
                modifier = Modifier.size(42.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(6.dp),
                ) {
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = title,
                        colorFilter = tint?.let { ColorFilter.tint(it) },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A),
                )
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

/** Slide 4: Pro Tips for Magic Demos */
@Composable
private fun GuideSlideTips(isDark: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF10B981).copy(alpha = 0.15f),
            border = BorderStroke(1.5.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("💡", fontSize = 36.sp)
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "Pro Tips for Magic Sharing",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isDark) Color.White else Color(0xFF0F172A),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TipRow("📶", "Same Wi-Fi Network", "Devices must be on the same local router (disable VPNs on both).", isDark)
            TipRow("🪄", "Magic Blackout Mode", "Tap Blackout Screen to turn phone pitch-black while hand camera stays active!", isDark)
            TipRow("🔍", "Retina Active App", "On Mac, select 'Active App' to share only your focused window without desktop clutter.", isDark)
        }
    }
}

@Composable
private fun TipRow(icon: String, title: String, text: String, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else Color(0xFF0F172A),
            )
            Text(
                text = text,
                fontSize = 11.5.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                lineHeight = 15.sp,
            )
        }
    }
}
