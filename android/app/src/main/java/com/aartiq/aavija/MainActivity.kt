package com.aartiq.aavija

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private lateinit var advertiser: BonjourAdvertiser
    private lateinit var macDiscovery: MacDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advertiser = BonjourAdvertiser(this)
        AavijaSounds.init(this)
        macDiscovery = MacDiscovery(this)
        requestLegacyStoragePermissionIfNeeded()

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(AppThemeMode.DARK) }

            AavijaAppTheme(themeMode = themeMode) {
                AdvertiserScreen(
                    advertiser = advertiser,
                    macDiscovery = macDiscovery,
                    themeMode = themeMode,
                    onThemeToggle = {
                        themeMode = themeMode.next()
                    },
                )
            }
        }
    }

    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    override fun onStart() {
        super.onStart()
        advertiser.start()
        macDiscovery.start()
    }

    override fun onStop() {
        macDiscovery.stop()
        advertiser.stop()
        super.onStop()
    }
}

@Composable
fun AdvertiserScreen(
    advertiser: BonjourAdvertiser,
    macDiscovery: MacDiscovery,
    themeMode: AppThemeMode,
    onThemeToggle: () -> Unit,
) {
    val state by advertiser.state.collectAsState()
    val pin by advertiser.typedPin.collectAsState()
    val macs by macDiscovery.macs.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val colors = LocalAavijaColors.current

    // Screenshot sharing: open photo viewer when a new image arrives
    var openImage by remember { mutableStateOf<BonjourAdvertiser.ReceivedImage?>(null) }
    var lastShownImageId by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.receivedImage?.eventId) {
        val image = state.receivedImage ?: return@LaunchedEffect
        if (image.eventId > lastShownImageId) {
            lastShownImageId = image.eventId
            openImage = image
        }
    }

    // Buzz on wrong PIN
    LaunchedEffect(state.wrongPinCount) {
        if (state.wrongPinCount > 0) buzzWrongPin(context)
    }

    var isBlackoutMode by rememberSaveable { mutableStateOf(false) }
    var showBlackoutHint by remember { mutableStateOf(false) }

    // Immersive fullscreen for blackout mode: hide status and navigation bars
    val activity = context as? ComponentActivity
    DisposableEffect(isBlackoutMode) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isBlackoutMode) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var blackoutCameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val blackoutCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        blackoutCameraGranted = granted
    }

    LaunchedEffect(isBlackoutMode, blackoutCameraGranted) {
        if (isBlackoutMode) {
            advertiser.setGestureMode(true)
            if (!blackoutCameraGranted) {
                blackoutCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    if (isBlackoutMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            isBlackoutMode = false
                            advertiser.setGestureMode(false)
                        },
                        onTap = {
                            showBlackoutHint = true
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Keep Gesture Share camera active behind the blackout screen!
            if (blackoutCameraGranted) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .graphicsLayer { alpha = 0.01f }
                ) {
                    GestureCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onPose = { /* pose tracked */ },
                        onEvent = { event ->
                            advertiser.onGesture(event)
                        },
                        onError = { /* camera problem */ },
                    )
                }
            }

            AnimatedVisibility(
                visible = showBlackoutHint,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(400)),
            ) {
                LaunchedEffect(showBlackoutHint) {
                    if (showBlackoutHint) {
                        delay(2500)
                        showBlackoutHint = false
                    }
                }
                Text(
                    text = "✨ Magic Mode • Gesture Camera Active • Double-tap to exit",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            TransferOrbOverlay(
                visible = state.isReceiving || state.isSending,
                label = state.transferStatus ?: "Sharing…",
            )

            openImage?.let { image ->
                ReceivedPhotoViewer(
                    image = image,
                    onDismiss = { openImage = null },
                )
            }
        }
        return
    }

    val selectedMac = state.selectedMac
    if (selectedMac != null && !state.isPaired) {
        PinEntryPopup(
            macName = selectedMac,
            pin = pin,
            state = state,
            onPinChange = { advertiser.setPin(it) },
            onSubmit = {
                advertiser.submitPin()
                focusManager.clearFocus()
            },
            onDismiss = { advertiser.selectMac(null) },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgGradient),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar with Logo and Theme Switch
            HeaderBar(
                themeMode = themeMode,
                onThemeToggle = onThemeToggle,
            )

            Spacer(Modifier.height(24.dp))

            // Primary Discoverability Hero Card
            DiscoverabilityCard(state = state)

            Spacer(Modifier.height(14.dp))

            // Magic Blackout Screen Button (for video demonstrations)
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, colors.surfaceBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showBlackoutHint = true
                        isBlackoutMode = true
                    }
                    .shadow(8.dp, RoundedCornerShape(20.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🪄", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Magic Blackout Screen",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Pitch black screen • Perfect for video demo",
                            fontSize = 11.5.sp,
                            color = colors.textSecondary,
                        )
                    }
                    Text(
                        text = "Enter →",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Available Macs Section
            AvailableMacsCard(
                macs = macs,
                selectedMac = selectedMac,
                isPaired = state.isPaired,
                pairedMacName = state.pairedMacName,
                onSelectMac = { advertiser.selectMac(it) },
            )

            // Connection / Pairing Status Banner
            if (state.lastPairing != null) {
                Spacer(Modifier.height(16.dp))
                StatusPill(
                    text = state.lastPairing!!,
                    isPaired = state.isPaired,
                    isWaiting = state.isWaitingForPin,
                )
            }

            // File Transfer Status Banner
            if (state.transferStatus != null) {
                Spacer(Modifier.height(14.dp))
                TransferStatusPill(
                    status = state.transferStatus!!,
                    isReceiving = state.isReceiving,
                    failed = state.transferFailed,
                )
            }

            // Quick Action: Send File to Mac (when paired)
            val scope = rememberCoroutineScope()
            val pickLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {}
                scope.launch {
                    val meta = withContext(Dispatchers.IO) { readNameSizeAndHash(context, uri) }
                    if (meta != null) {
                        advertiser.queueOutgoingFile(uri, meta.first, meta.second, meta.third)
                    } else {
                        advertiser.reportOutgoingProblem("Couldn't read that file.")
                    }
                }
            }

            if (state.isPaired) {
                Spacer(Modifier.height(20.dp))
                SendFileCard(onPickFile = { pickLauncher.launch(arrayOf("*/*")) })
            }

            Spacer(Modifier.height(24.dp))

            // Gesture Mode Interactive Card
            GestureModeSection(
                context = context,
                state = state,
                advertiser = advertiser,
            )

            Spacer(Modifier.height(36.dp))
        }

        // Phase 8: Quantum Orb Overlay while transfer is actively moving bytes
        TransferOrbOverlay(
            visible = state.isReceiving || state.isSending,
            label = state.transferStatus ?: "Sharing…",
        )

        // Screenshot sharing: Photo viewer with smooth spring unfold & swipe dismiss
        openImage?.let { image ->
            ReceivedPhotoViewer(
                image = image,
                onDismiss = { openImage = null },
            )
        }
    }
}

@Composable
private fun HeaderBar(
    themeMode: AppThemeMode,
    onThemeToggle: () -> Unit,
) {
    val colors = LocalAavijaColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isDark = themeMode == AppThemeMode.DARK
            val logoBg = if (isDark) Color.Black else Color.White
            val logoBorder = if (isDark) Color.White.copy(alpha = 0.22f) else Color(0xFF00E5FF).copy(alpha = 0.35f)
            val logoDrawable = if (isDark) R.drawable.app_logo_bright else R.drawable.app_logo

            // Brand Logo ("આવી જા")
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = logoBg,
                border = BorderStroke(1.dp, logoBorder),
                shadowElevation = if (isDark) 0.dp else 3.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = logoDrawable),
                        contentDescription = "Aavija Logo",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    "Aavija",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    "Gesture AirDrop over Wi-Fi",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                )
            }
        }

        // Theme Toggle Pill Button
        Surface(
            color = colors.surfaceElevated,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.surfaceBorder),
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onThemeToggle,
                )
                .shadow(4.dp, RoundedCornerShape(16.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (colors.isDark) "🌙" else "☀️",
                    fontSize = 15.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (colors.isDark) "Dark" else "Light",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun DiscoverabilityCard(state: BonjourAdvertiser.AdState) {
    val colors = LocalAavijaColors.current
    val pulseMotion = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseMotion.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.surfaceBorder),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.05f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Live Status Beacon
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .graphicsLayer {
                        scaleX = if (state.isAdvertising) pulseScale else 1f
                        scaleY = if (state.isAdvertising) pulseScale else 1f
                    }
                    .clip(CircleShape)
                    .background(
                        if (state.isAdvertising) colors.statusSuccess.copy(alpha = 0.16f)
                        else colors.statusWarning.copy(alpha = 0.16f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.isAdvertising) colors.statusSuccess
                            else colors.statusWarning
                        ),
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = if (state.isAdvertising) "Discoverable on this Wi-Fi" else "Starting discovery…",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Device: ",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Text(
                    text = state.serviceName ?: "Android",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                Text(
                    text = "  •  Port: ",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Text(
                    text = "${state.port}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AvailableMacsCard(
    macs: List<MacDiscovery.MacDevice>,
    selectedMac: String?,
    isPaired: Boolean,
    pairedMacName: String?,
    onSelectMac: (String) -> Unit,
) {
    val colors = LocalAavijaColors.current

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.surfaceBorder),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "AVAILABLE MACS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                    letterSpacing = 1.sp,
                )

                if (macs.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (macs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = colors.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Searching for Macs on this network…\nEnsure Aavija is active in your Mac menu bar",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    macs.forEach { mac ->
                        val isThisPaired = isPaired && (pairedMacName == mac.name || pairedMacName == null)
                        val isChosen = mac.name == selectedMac

                        Surface(
                            color = if (isChosen || isThisPaired) colors.surfaceElevated else Color.Transparent,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                width = if (isChosen || isThisPaired) 1.5.dp else 1.dp,
                                color = if (isThisPaired) colors.statusSuccess
                                else if (isChosen) colors.primary
                                else colors.surfaceBorder,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMac(mac.name) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Laptop Icon
                                ModernLaptopIcon(tint = if (isThisPaired) colors.statusSuccess else colors.primary)

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mac.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = if (isThisPaired) "Trusted device • ready to share" else "Tap to pair",
                                        fontSize = 12.sp,
                                        color = if (isThisPaired) colors.statusSuccess else colors.textTertiary,
                                    )
                                }

                                // Status Tag
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isThisPaired) colors.statusSuccess.copy(alpha = 0.16f)
                                            else colors.primary.copy(alpha = 0.14f)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = if (isThisPaired) "Paired ✓" else "Pair →",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isThisPaired) colors.statusSuccess else colors.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, isPaired: Boolean, isWaiting: Boolean) {
    val colors = LocalAavijaColors.current
    val pillColor = when {
        isPaired -> colors.statusSuccess
        isWaiting -> colors.statusWarning
        else -> colors.statusError
    }

    Surface(
        color = pillColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(0.92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(pillColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = pillColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TransferStatusPill(status: String, isReceiving: Boolean, failed: Boolean) {
    val colors = LocalAavijaColors.current
    val pillColor = when {
        failed -> colors.statusError
        isReceiving -> colors.statusWarning
        else -> colors.statusSuccess
    }

    Surface(
        color = pillColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(0.92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = status,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = pillColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SendFileCard(onPickFile: () -> Unit) {
    val colors = LocalAavijaColors.current

    Surface(
        color = colors.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPickFile() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "📁",
                fontSize = 20.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Send file to Mac…",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun GestureModeSection(
    context: Context,
    state: BonjourAdvertiser.AdState,
    advertiser: BonjourAdvertiser,
) {
    val colors = LocalAavijaColors.current
    var gestureMode by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraProblem by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            cameraProblem = "Camera permission is needed for gesture mode."
            gestureMode = false
        }
    }

    LaunchedEffect(gestureMode, cameraGranted, state.isAdvertising) {
        advertiser.setGestureMode(gestureMode && cameraGranted)
        if (gestureMode && !cameraGranted) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (gestureMode) colors.primary.copy(alpha = 0.40f) else colors.surfaceBorder,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🖐", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Gesture Mode",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            "Camera tracks hands in real-time",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                        )
                    }
                }

                Switch(
                    checked = gestureMode,
                    onCheckedChange = { on ->
                        cameraProblem = null
                        gestureMode = on
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.textTertiary,
                        uncheckedTrackColor = colors.surfaceElevated,
                    ),
                )
            }

            if (gestureMode && cameraGranted) {
                Spacer(Modifier.height(18.dp))
                var pose by remember { mutableStateOf(HandPose.UNKNOWN) }
                var lastEvent by remember { mutableStateOf<String?>(null) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, colors.primary.copy(alpha = 0.45f), RoundedCornerShape(20.dp)),
                ) {
                    GestureCameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        onPose = { pose = it },
                        onEvent = { event ->
                            lastEvent = when (event) {
                                HandGestureMachine.EventType.GRAB -> "GRAB ✊"
                                HandGestureMachine.EventType.RELEASE -> "RELEASE 🖐"
                            }
                            advertiser.onGesture(event)
                        },
                        onError = { cameraProblem = it },
                    )

                    // Floating Pose Indicator Badge inside Camera Preview
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val poseEmoji = when (pose) {
                                HandPose.OPEN -> "🖐"
                                HandPose.FIST -> "✊"
                                HandPose.UNKNOWN -> "🔍"
                            }
                            val poseLabel = when (pose) {
                                HandPose.OPEN -> "Open hand — ready to grab"
                                HandPose.FIST -> "Fist — holding"
                                HandPose.UNKNOWN -> "Show your hand to the camera"
                            }
                            Text(poseEmoji, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                poseLabel,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }

                lastEvent?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Last gesture: $it",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.isPaired) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = colors.surfaceElevated,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "HOW TO SHARE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                letterSpacing = 0.8.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "• To receive screenshot: GRAB on your Mac with no file held, then RELEASE here — the photo unfolds instantly!\n• To send: GRAB here, then RELEASE on your Mac within 10s.",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }

            cameraProblem?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    fontSize = 13.sp,
                    color = colors.statusError,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModernLaptopIcon(tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 20.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(tint.copy(alpha = 0.18f))
                .border(1.5.dp, tint, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 6.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint.copy(alpha = 0.40f)),
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 3.5.dp)
                .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                .background(tint),
        )
    }
}

@Composable
private fun PinEntryPopup(
    macName: String,
    pin: String,
    state: BonjourAdvertiser.AdState,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAavijaColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var isSubmitting by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(state.lockedUntilMs) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= state.lockedUntilMs) break
            delay(1_000)
        }
    }
    val lockRemainingMs = (state.lockedUntilMs - now).coerceAtLeast(0)
    val locked = lockRemainingMs > 0

    LaunchedEffect(state.lastRejection, state.wrongPinCount, state.isPaired) {
        isSubmitting = false
    }

    LaunchedEffect(isSubmitting) {
        if (isSubmitting) {
            delay(6_000)
            isSubmitting = false
        }
    }

    val triggerSubmit = {
        if (!locked && pin.length == 6 && !isSubmitting) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            isSubmitting = true
            onSubmit()
        }
    }

    AlertDialog(
        onDismissRequest = {
            isSubmitting = false
            onDismiss()
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                "Pair with $macName",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (locked) {
                    Text(
                        "Too many wrong PINs. Try again in " +
                            "${lockRemainingMs / 60_000}m " +
                            "${(lockRemainingMs % 60_000) / 1_000}s",
                        color = colors.statusError,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        "Enter the 6-digit PIN shown on your Mac.",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { newPin ->
                            isSubmitting = false
                            onPinChange(newPin)
                            val clean = newPin.filter { it.isDigit() }.take(6)
                            if (clean.length == 6 && !locked) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isSubmitting = true
                                onSubmit()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { triggerSubmit() }
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            letterSpacing = 8.sp,
                            color = colors.textPrimary,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.surfaceBorder,
                            focusedContainerColor = colors.surfaceElevated,
                            unfocusedContainerColor = colors.surfaceElevated,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (state.attemptsRemaining < PinPairing.MAX_ATTEMPTS) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${state.attemptsRemaining} of ${PinPairing.MAX_ATTEMPTS} attempts left",
                            fontSize = 13.sp,
                            color = colors.statusError,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (state.lastRejection != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.lastRejection!!,
                            fontSize = 13.sp,
                            color = colors.statusError,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (isSubmitting) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.primary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Verifying with your Mac…",
                                fontSize = 13.sp,
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else if (state.isWaitingForPin) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Waiting for your Mac…",
                            fontSize = 13.sp,
                            color = colors.statusWarning,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { triggerSubmit() },
                enabled = !locked && pin.length == 6 && !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = if (colors.isDark) Color.Black else Color.White,
                    disabledContainerColor = if (isSubmitting) colors.primary else colors.surfaceElevated,
                    disabledContentColor = if (isSubmitting) Color.White else colors.textTertiary,
                ),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = if (colors.isDark) Color.Black else Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Verifying…",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (colors.isDark) Color.Black else Color.White,
                    )
                } else {
                    Text(
                        "Submit",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    isSubmitting = false
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Cancel",
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

private fun buzzWrongPin(context: Context) {
    try {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            (manager as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    } catch (_: Exception) {}
}

private fun readNameSizeAndHash(context: Context, uri: Uri): Triple<String, Long, String>? {
    var name = "file"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
            if (sizeIdx >= 0) size = c.getLong(sizeIdx)
        }
    }
    if (size <= 0) return null
    val sha = try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } ?: return null
        PairingCrypto.toHex(digest.digest())
    } catch (_: Exception) {
        return null
    }
    return Triple(name, size, sha)
}
