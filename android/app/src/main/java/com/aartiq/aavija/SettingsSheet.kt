package com.aartiq.aavija

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Settings Sheet for Aavija.
 *
 * Allows configuring:
 * - Theme mode (System / Dark / Light)
 * - Background gesture scanning & foreground service
 * - Screen capture service
 * - Reset pairing trust cache
 * - Launching Startup Guide
 * - Creator & Project metadata
 */
@Composable
fun SettingsSheet(
    themeMode: AppThemeMode,
    onThemeSelect: (AppThemeMode) -> Unit,
    onOpenGuide: () -> Unit,
    onEnterBlackout: () -> Unit,
    onResetPairing: () -> Unit,
    onDismiss: () -> Unit,
    pairedMacName: String? = null,
    pairedPhoneName: String? = null,
    onUnpairDevice: ((String?) -> Unit)? = null,
    onOpenGemini: (() -> Unit)? = null,
    authManager: AavijaAuthManager? = null,
    onSignInGoogle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAavijaColors.current
    val isDark = colors.isDark
    val context = LocalContext.current

    var bgGestureEnabled by remember { mutableStateOf(true) }
    var screenCaptureServiceEnabled by remember { mutableStateOf(true) }
    var autoDiscoveryEnabled by remember { mutableStateOf(true) }

    val glassBg = if (isDark) Color(0xEE090D1A) else Color(0xF2FFFFFF)
    val glassBorder = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0x330284C7)

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
                .fillMaxSize(0.88f)
                .clickable(enabled = false) {}
                .shadow(32.dp, RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            color = glassBg,
            border = BorderStroke(1.2.dp, glassBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                // Header Bar: Settings Title & Close Button
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
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("⚙️", fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Settings",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000),
                        modifier = Modifier
                            .size(32.dp)
                            .clickable(onClick = onDismiss),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("✕", fontSize = 14.sp, color = colors.textSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Scrollable Settings Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Section 0: Firebase & Google Sign-In
                    val authUser = authManager?.user?.collectAsState()?.value
                    val authStatus = authManager?.statusMessage?.collectAsState()?.value

                    SettingsSection(title = "🔥 Google Account & Firebase", isDark = isDark) {
                        if (authUser != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isDark) Color(0xFF131D33) else Color(0xFFF1F5F9),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                                modifier = Modifier.size(46.dp),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    if (!authUser.photoUrl.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = authUser.photoUrl,
                                                            contentDescription = authUser.displayName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .clip(CircleShape),
                                                        )
                                                    } else {
                                                        Text(
                                                            text = (authUser.displayName?.firstOrNull() ?: authUser.email?.firstOrNull() ?: 'U').uppercase(),
                                                            fontSize = 18.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = colors.textPrimary,
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = authUser.displayName ?: "Google User",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = authUser.email ?: "Firebase Authenticated",
                                                    fontSize = 12.sp,
                                                    color = colors.textSecondary,
                                                    maxLines = 1,
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                                            modifier = Modifier.clickable { authManager?.signOut() },
                                        ) {
                                            Text(
                                                text = "Sign Out",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFEF4444),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF10B981)),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "Firebase Auth Active • Verified Identity",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF10B981),
                                        )
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isDark) Color(0xFF131D33) else Color(0xFFF1F5F9),
                                border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSignInGoogle?.invoke() },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White,
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(text = "G", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF4285F4))
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = "Sign in with Google",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.textPrimary,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "Sync verified trust & devices via Firebase",
                                                fontSize = 11.sp,
                                                color = colors.textSecondary,
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.primary.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = "Sign In",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (!authStatus.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = authStatus,
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }

                    // Section 1: Appearance & Theme Mode
                    SettingsSection(title = "🎨 Appearance & Theme", isDark = isDark) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isDark) Color(0xFF131D33) else Color(0xFFF1F5F9))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AppThemeMode.entries.forEach { mode ->
                                val isSelected = themeMode == mode
                                val label = when (mode) {
                                    AppThemeMode.SYSTEM -> "System"
                                    AppThemeMode.DARK -> "Dark"
                                    AppThemeMode.LIGHT -> "Light"
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onThemeSelect(mode) },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF060911) else colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Background & Foreground Services
                    SettingsSection(title = "⚡ Background & Services", isDark = isDark) {
                        SettingsToggleRow(
                            title = "Background Gesture Scanner",
                            subtitle = "Keep camera analysing hand gestures when app is in background.",
                            checked = bgGestureEnabled,
                            onCheckedChange = { bgGestureEnabled = it },
                            isDark = isDark,
                        )

                        Spacer(Modifier.height(12.dp))

                        SettingsToggleRow(
                            title = "Screen Capture Service",
                            subtitle = "Enable foreground media projection for one-shot screenshot sharing.",
                            checked = screenCaptureServiceEnabled,
                            onCheckedChange = { screenCaptureServiceEnabled = it },
                            isDark = isDark,
                        )

                        Spacer(Modifier.height(12.dp))

                        SettingsToggleRow(
                            title = "Automatic Wi-Fi mDNS Discovery",
                            subtitle = "Continuously scan local subnet for Mac, Windows & Android peers.",
                            checked = autoDiscoveryEnabled,
                            onCheckedChange = { autoDiscoveryEnabled = it },
                            isDark = isDark,
                        )
                    }

                    // Section 3: Quick Actions & Guides
                    SettingsSection(title = "✨ Quick Actions", isDark = isDark) {
                        SettingsActionRow(
                            icon = "🪄",
                            title = "Enter Magic Blackout Mode",
                            desc = "Pitch-black screen with active gesture camera.",
                            onClick = {
                                onDismiss()
                                onEnterBlackout()
                            },
                            isDark = isDark,
                        )

                        Spacer(Modifier.height(10.dp))

                        SettingsActionRow(
                            icon = "📖",
                            title = "Replay Startup Walkthrough",
                            desc = "View animated guide on gestures, pairing & installation.",
                            onClick = {
                                onDismiss()
                                onOpenGuide()
                            },
                            isDark = isDark,
                        )
                    }

                    // Section 4: Security & Pairing
                    SettingsSection(title = "🛡️ Security & Pairing", isDark = isDark) {
                        if (pairedMacName != null) {
                            val isWin = pairedMacName.contains("Windows", ignoreCase = true) || pairedMacName.contains("PC", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isWin) "🪟" else "🍎", fontSize = 16.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = pairedMacName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary,
                                        )
                                        Text(
                                            text = if (isWin) "Windows PC • TLS 1.3" else "macOS • TLS 1.3",
                                            fontSize = 11.sp,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                                    modifier = Modifier.clickable {
                                        onUnpairDevice?.invoke(pairedMacName)
                                    },
                                ) {
                                    Text(
                                        text = "Unpair ✕",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        if (pairedPhoneName != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📱", fontSize = 16.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = pairedPhoneName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.textPrimary,
                                        )
                                        Text(
                                            text = "Android Phone • Paired",
                                            fontSize = 11.sp,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                                    modifier = Modifier.clickable {
                                        onUnpairDevice?.invoke(pairedPhoneName)
                                    },
                                ) {
                                    Text(
                                        text = "Unpair ✕",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF5252),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        if (onOpenGemini != null) {
                            SettingsActionRow(
                                icon = "✨",
                                title = "Ask Google Gemini AI",
                                desc = "Diagnose network, check connected devices & unpair with AI.",
                                onClick = {
                                    onDismiss()
                                    onOpenGemini()
                                },
                                isDark = isDark,
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        SettingsActionRow(
                            icon = "🔑",
                            title = "Reset All Paired Devices",
                            desc = "Clear all pinned TLS 1.3 certificates and pairing tokens.",
                            onClick = onResetPairing,
                            isDark = isDark,
                            isDestructive = true,
                        )
                    }

                    // Section 5: About & Developer Credits
                    SettingsSection(title = "ℹ️ About Aavija", isDark = isDark) {
                        val appVersion = remember(context) {
                            runCatching {
                                val pInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    context.packageManager.getPackageInfo(
                                        context.packageName,
                                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    context.packageManager.getPackageInfo(context.packageName, 0)
                                }
                                pInfo.versionName ?: "1.2.0"
                            }.getOrDefault("1.2.0")
                        }
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "Aavija (આવી જા) v$appVersion",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Native hand-gesture file & screenshot sharing over local Wi-Fi without cloud servers.",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 16.sp,
                            )
                            Spacer(Modifier.height(12.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isDark) Color(0xFF19233C) else Color(0xFFE2E8F0),
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/_preert_/"))
                                        context.startActivity(intent)
                                    },
                                ) {
                                    Text(
                                        text = "📸 Instagram: @_preert_",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFF4081),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isDark) Color(0xFF19233C) else Color(0xFFE2E8F0),
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aavija.vercel.app"))
                                        context.startActivity(intent)
                                    },
                                ) {
                                    Text(
                                        text = "🌐 aavija.vercel.app",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF00E5FF),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) Color(0xFF0D1424) else Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155),
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDark) Color.White else Color(0xFF0F172A),
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                lineHeight = 15.sp,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF060911),
                checkedTrackColor = Color(0xFF00E5FF),
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: String,
    title: String,
    desc: String,
    onClick: () -> Unit,
    isDark: Boolean,
    isDestructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isDark) Color(0xFF172036) else Color(0xFFE2E8F0),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(icon, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDestructive) Color(0xFFEF4444) else (if (isDark) Color.White else Color(0xFF0F172A)),
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            )
        }
        Text(
            text = "→",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDestructive) Color(0xFFEF4444) else Color(0xFF00E5FF),
        )
    }
}
