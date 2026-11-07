package com.aartiq.aavija

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

    private lateinit var runtime: AavijaRuntime
    private lateinit var advertiser: BonjourAdvertiser
    private lateinit var macDiscovery: MacDiscovery
    private lateinit var peerDiscovery: PeerDiscovery
    private lateinit var peerConnector: PeerConnector

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // One runtime for the whole process: the foreground service keeps the
        // same camera, listener, and peer connector alive after this activity
        // is backgrounded. Creating fresh copies here would silently kill
        // gesture mode on every onStop/onStart cycle.
        runtime = AavijaRuntime.get(this)
        advertiser = runtime.advertiser
        macDiscovery = runtime.macDiscovery
        peerDiscovery = runtime.peerDiscovery
        peerConnector = runtime.peerConnector
        requestLegacyStoragePermissionIfNeeded()

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(AppThemeMode.DARK) }

            AavijaAppTheme(themeMode = themeMode) {
                AdvertiserScreen(
                    runtime = runtime,
                    advertiser = advertiser,
                    macDiscovery = macDiscovery,
                    peerDiscovery = peerDiscovery,
                    peerConnector = peerConnector,
                    initialBlackout = intent.getBooleanExtra("enter_blackout", false),
                    initialImageUri = intent.getStringExtra("show_image_uri"),
                    initialImageName = intent.getStringExtra("show_image_name"),
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
        runtime.onUiStarted()
    }

    override fun onStop() {
        // If the foreground service is running this deliberately does nothing
        // to the camera/network; otherwise the runtime stops the Wi-Fi session.
        runtime.onUiStopped()
        super.onStop()
    }

    override fun onDestroy() {
        // Never shutdown() the peer connector here: the service may still be
        // running and would lose its heartbeat job forever. The runtime lives
        // as long as the process, exactly like the service.
        super.onDestroy()
    }
}

@Composable
fun AdvertiserScreen(
    runtime: AavijaRuntime,
    advertiser: BonjourAdvertiser,
    macDiscovery: MacDiscovery,
    peerDiscovery: PeerDiscovery,
    peerConnector: PeerConnector,
    initialBlackout: Boolean = false,
    initialImageUri: String? = null,
    initialImageName: String? = null,
    themeMode: AppThemeMode,
    onThemeToggle: () -> Unit,
) {
    val state by advertiser.state.collectAsState()
    val pin by advertiser.typedPin.collectAsState()
    val macs by macDiscovery.macs.collectAsState()
    val peers by peerDiscovery.peers.collectAsState()
    val peerState by peerConnector.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val colors = LocalAavijaColors.current

    // The device a queued file is headed for. The phone peer is the most
    // recently activated target; tapping a Mac switches the queue back.
    val targetName: String? = when {
        peerState.isPaired && peerState.targetActive && !peerState.pairingFailed ->
            peerState.pairedPeerName ?: "phone"
        // On the listener side a phone client can be the latest pairing; it is
        // tracked separately so the Mac row keeps its own name. Only show it
        // when that phone actually owns the queue.
        state.phoneTargetActive && state.pairedPhoneName != null ->
            state.pairedPhoneName
        state.isPaired && state.pairedMacName != null -> state.pairedMacName
        else -> null
    }

    // Screenshot sharing: open photo viewer when a new image arrives
    var openImage by remember { mutableStateOf<BonjourAdvertiser.ReceivedImage?>(null) }
    var lastShownImageId by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(initialImageUri) {
        if (initialImageUri != null) {
            openImage = BonjourAdvertiser.ReceivedImage(
                uri = Uri.parse(initialImageUri),
                name = initialImageName ?: "image",
                eventId = -1L,
            )
        }
    }
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

    var isBlackoutMode by rememberSaveable { mutableStateOf(initialBlackout) }
    var showExitWarning by remember { mutableStateOf(false) }

    BackHandler(enabled = state.gestureMode) {
        showExitWarning = true
    }
    var showBlackoutHint by remember { mutableStateOf(false) }
    var gestureWasOnBeforeBlackout by remember { mutableStateOf(false) }
    var blackoutCaptureProblem by remember { mutableStateOf<String?>(null) }

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
    val requestBlackoutProjection = rememberScreenCaptureRequester(context) {
        blackoutCaptureProblem = it
    }

    LaunchedEffect(isBlackoutMode, blackoutCameraGranted) {
        if (isBlackoutMode) {
            if (!blackoutCameraGranted) {
                blackoutCameraPermission.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }
            // The service owns the camera now, so blackout mode survives a
            // screen-off or app switch just like normal gesture mode.
            GestureShareService.start(context)
            if (!runtime.screenCapture.isActive()) {
                requestBlackoutProjection()
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
                            // Restore whatever the user had before entering
                            // blackout; don't silently switch gesture mode off.
                            if (!gestureWasOnBeforeBlackout) {
                                GestureShareService.stop(context)
                            }
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
                        controller = runtime.gestureCamera,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            blackoutCaptureProblem?.let { problem ->
                Text(
                    text = problem,
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                )
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
    // Both a Mac AND a phone can ask us to pair. `pairingRequestActive` is the
    // honest "a prompt is live" flag: `isPaired` only means we have SOME token,
    // so it must not suppress a new phone's prompt.
    if (selectedMac != null && state.pairingRequestActive && !peerState.isPairing) {
        PinEntryPopup(
            deviceName = selectedMac,
            pin = pin,
            state = state,
            onPinChange = { advertiser.setPin(it) },
            onSubmit = {
                advertiser.submitPin()
                focusManager.clearFocus()
            },
            onDismiss = { advertiser.dismissPairingRequest() },
        )
    }

    // The CONNECTING phone shows the PIN; the other phone types it.
    if (peerState.isPairing) {
        PeerPinDialog(
            peerName = peerState.pairingPeerName ?: "phone",
            pin = peerState.pin ?: "",
            status = peerState.pairingStatus,
            failed = peerState.pairingFailed,
            onCancel = { peerConnector.cancelPairing() },
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
                        gestureWasOnBeforeBlackout = state.gestureMode
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
                onSelectMac = {
                    // Picking a Mac makes it the send target; a file must not be
                    // pushed/pulled by a paired phone behind the user's back.
                    advertiser.setMacTargetActive(true)
                    peerConnector.pauseTarget()
                    advertiser.selectMac(it)
                },
            )

            Spacer(Modifier.height(14.dp))

            // Other phones running Aavija.
            AvailablePhonesCard(
                peers = peers,
                pairedIds = peerState.pairedPeerIds,
                activePeerId = peerState.activePeerId,
                pairedPeerName = peerState.pairedPeerName,
                trustedPhoneName = state.pairedPhoneName,
                phoneTargetActive = state.phoneTargetActive,
                isPaired = peerState.isPaired,
                isReachable = peerState.isReachable,
                targetActive = peerState.targetActive,
                onSelect = { peer ->
                    // A phone tap switches the single queue back to phone mode.
                    advertiser.setMacTargetActive(false)
                    // If this phone is the one that paired with US, it already
                    // has a token here and dials us; do not start a redundant
                    // reverse pairing. Just give it the queue back.
                    val alreadyOurs = peerConnector.isPairedWith(peer.id)
                    if (!alreadyOurs && advertiser.resumePhoneTarget(peer.name)) {
                        // Listener-side peer: let it pull, and stop pushing the
                        // queue to any outbound peer we were driving.
                        peerConnector.pauseTarget()
                    } else {
                        peerConnector.pairWith(peer)
                    }
                },
            )

            // Client-side pairing status ("Waiting for Pixel…", "Wrong PIN").
            if (!peerState.isPairing && peerState.pairingStatus != null) {
                Spacer(Modifier.height(14.dp))
                StatusPill(
                    text = peerState.pairingStatus!!,
                    isPaired = peerState.isPaired && !peerState.pairingFailed,
                    isWaiting = false,
                )
            }

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
                        // If a phone is the active target, send it right away
                        // (unless Gesture mode is waiting for a grab/release).
                        peerConnector.onFileQueued()
                    } else {
                        advertiser.reportOutgoingProblem("Couldn't read that file.")
                    }
                }
            }

            if (targetName != null) {
                Spacer(Modifier.height(20.dp))
                SendFileCard(
                    label = "Send file to $targetName…",
                    onPickFile = { pickLauncher.launch(arrayOf("*/*")) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // Gesture Mode Interactive Card
            GestureModeSection(
                context = context,
                runtime = runtime,
                state = state,
                advertiser = advertiser,
                peerConnector = peerConnector,
                targetName = targetName,
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

        if (showExitWarning) {
            AlertDialog(
                onDismissRequest = { showExitWarning = false },
                title = { Text("Gesture sharing is on") },
                text = { Text("Closing the app while Gesture Mode is on keeps the camera running in the background and may drain battery. Screen recording permission is required for screenshots.") },
                confirmButton = {
                    TextButton(onClick = {
                        showExitWarning = false
                        GestureShareService.stop(context)
                    }) { Text("Stop & exit") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitWarning = false }) { Text("Keep running") }
                },
            )
        }
    }
}

@Composable
private fun rememberScreenCaptureRequester(
    context: Context,
    onProblem: (String?) -> Unit,
): () -> Unit {
    val manager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            GestureShareService.grantProjection(context, result.resultCode, data)
            onProblem(null)
        } else {
            onProblem("Screen capture permission is needed to share screenshots.")
        }
    }
    return remember(launcher, manager) {
        {
            runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
                .onFailure { onProblem("Couldn't open the screen-capture dialog.") }
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
                        // `isPaired` means "we trust SOME device", which can now
                        // be another phone. Only name a Mac as trusted when it is
                        // the Mac this phone actually paired with; otherwise an
                        // Android-only pairing would make every Mac row claim
                        // "Paired ✓" without a Mac token.
                        val isThisPaired = isPaired && pairedMacName == mac.name
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

private data class PhoneRow(
    val id: String,
    val name: String,
    val online: Boolean,
    val peer: PeerDiscovery.PhonePeer?,
)

/**
 * Other phones running Aavija on this Wi-Fi.
 *
 * Ordering is deliberately derived from [PeerDiscovery]'s stable name order
 * (with the paired-but-offline phone appended in the same sort). A row never
 * jumps while the user is reaching for it.
 */
@Composable
private fun AvailablePhonesCard(
    peers: List<PeerDiscovery.PhonePeer>,
    pairedIds: Set<String>,
    activePeerId: String?,
    pairedPeerName: String?,
    /**
     * The phone that paired with US as its listener (we did not dial it). It is
     * tracked by name because the listener's outbound store never sees it.
     */
    trustedPhoneName: String?,
    /** Does that listener-side phone currently own the outgoing queue? */
    phoneTargetActive: Boolean,
    isPaired: Boolean,
    isReachable: Boolean,
    targetActive: Boolean,
    onSelect: (PeerDiscovery.PhonePeer) -> Unit,
) {
    val colors = LocalAavijaColors.current

    val rows = buildList {
        peers.forEach { add(PhoneRow(it.id, it.name, online = true, peer = it)) }
        if (isPaired && activePeerId != null && peers.none { it.id == activePeerId }) {
            add(
                PhoneRow(
                    id = activePeerId,
                    name = pairedPeerName ?: "Paired phone",
                    online = false,
                    peer = null,
                ),
            )
        }
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

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
                    "OTHER PHONES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                    letterSpacing = 1.sp,
                )

                if (rows.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (rows.isEmpty()) {
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
                            "Searching for other phones…\nOpen Aavija on the second phone",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { row ->
                        val isCurrent = isPaired && row.id == activePeerId
                        // The listener never dialled this phone, but the phone
                        // paired with us: its row is still trusted.
                        val pairedHere = trustedPhoneName != null &&
                            row.name == trustedPhoneName
                        val trusted = row.id in pairedIds || isCurrent || pairedHere
                        // Trust is not the same as "owns the queue": tapping a
                        // Mac pauses the phone target without unpairing it.
                        val chosen = if (isCurrent) {
                            targetActive
                        } else {
                            pairedHere && phoneTargetActive
                        }

                        val subtitle = when {
                            !row.online -> "Paired • offline"
                            chosen -> if (isReachable || pairedHere) {
                                "Trusted • ready to share"
                            } else {
                                "Trusted • connecting…"
                            }
                            isCurrent -> "Trusted • tap to send here"
                            trusted -> "Trusted • tap to switch"
                            else -> "Tap to pair"
                        }

                        Surface(
                            color = if (chosen || isCurrent) colors.surfaceElevated else Color.Transparent,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                width = if (chosen) 1.5.dp else 1.dp,
                                color = when {
                                    chosen -> colors.primary
                                    trusted -> colors.statusSuccess
                                    else -> colors.surfaceBorder
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = row.online) {
                                    row.peer?.let(onSelect)
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("📱", fontSize = 17.sp)
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = row.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = subtitle,
                                        fontSize = 12.sp,
                                        color = when {
                                            chosen || isCurrent -> colors.primary
                                            trusted -> colors.statusSuccess
                                            else -> colors.textTertiary
                                        },
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (trusted) colors.statusSuccess.copy(alpha = 0.16f)
                                            else colors.primary.copy(alpha = 0.14f)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = when {
                                            !row.online -> "Offline"
                                            chosen -> "Sending ✓"
                                            trusted -> "Paired ✓"
                                            else -> "Pair →"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (trusted) colors.statusSuccess else colors.primary,
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
private fun SendFileCard(label: String, onPickFile: () -> Unit) {
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
                text = label,
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
    runtime: AavijaRuntime,
    state: BonjourAdvertiser.AdState,
    advertiser: BonjourAdvertiser,
    peerConnector: PeerConnector,
    targetName: String?,
) {
    val colors = LocalAavijaColors.current
    // The switch follows the service, not a local flag, so it stays correct
    // after a pause/resume or process restart.
    val gestureMode = state.gestureMode
    var wantGestureMode by remember { mutableStateOf(state.gestureMode) }
    LaunchedEffect(state.gestureMode) { wantGestureMode = state.gestureMode }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraProblem by remember { mutableStateOf<String?>(null) }

    val screenCaptureActive by runtime.screenCapture.active.collectAsState()
    val screenCaptureError by runtime.screenCapture.lastError.collectAsState()
    var captureProblem by remember { mutableStateOf<String?>(null) }
    val requestProjection = rememberScreenCaptureRequester(context) { captureProblem = it }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val startGestureMode: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        GestureShareService.start(context)
        if (!screenCaptureActive) {
            requestProjection()
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            cameraProblem = "Camera permission is needed for gesture mode."
            wantGestureMode = false
        } else if (wantGestureMode) {
            startGestureMode()
        }
    }

    LaunchedEffect(wantGestureMode, cameraGranted) {
        if (wantGestureMode && cameraGranted) {
            startGestureMode()
        } else if (!wantGestureMode) {
            GestureShareService.stop(context)
        }
    }

    val cameraError by runtime.gestureCamera.error.collectAsState()

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
                        captureProblem = null
                        if (on && !cameraGranted) {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                        wantGestureMode = on
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
                val pose by runtime.gestureCamera.pose.collectAsState()
                val cameraError by runtime.gestureCamera.error.collectAsState()
                LaunchedEffect(cameraError) {
                    if (cameraError != null) cameraProblem = cameraError
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, colors.primary.copy(alpha = 0.45f), RoundedCornerShape(20.dp)),
                ) {
                    GestureCameraPreview(
                        controller = runtime.gestureCamera,
                        modifier = Modifier.fillMaxSize(),
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

                state.lastGesture?.let {
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

                if (targetName != null) {
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
                                "• To receive: GRAB on $targetName, then RELEASE here — the file arrives instantly!\n" +
                                    "• To send a file: GRAB here, then RELEASE on $targetName within 10s.\n" +
                                    "• To send a screenshot: GRAB here captures your screen, RELEASE on $targetName shares it.\n" +
                                    "• Gestures keep working when Aavija is in the background.\n" +
                                    "• Warning: leaving Gesture Mode on uses the camera in the background and may drain battery. Screen recording permission is required for screenshots.",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }

            if (gestureMode) {
                Spacer(Modifier.height(10.dp))
                if (screenCaptureActive) {
                    Text(
                        "Screenshots ready • GRAB captures your screen, RELEASE shares it",
                        fontSize = 12.sp,
                        color = colors.statusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Surface(
                        color = colors.surfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.statusWarning),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                captureProblem ?: screenCaptureError
                                    ?: "Screen capture is off. Enable it to GRAB screenshots.",
                                fontSize = 12.sp,
                                color = colors.statusWarning,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { requestProjection() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Enable screenshot sharing")
                            }
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
private fun PeerPinDialog(
    peerName: String,
    pin: String,
    status: String?,
    failed: Boolean,
    onCancel: () -> Unit,
) {
    val colors = LocalAavijaColors.current

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.surface,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                "Pair with $peerName",
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
                Text(
                    "Type this PIN into $peerName. It works once and is never sent over Wi-Fi.",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = colors.surfaceElevated,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = pin.ifEmpty { "······" },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        letterSpacing = 8.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
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
                        "Waiting for $peerName…",
                        fontSize = 13.sp,
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (status != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        status,
                        fontSize = 13.sp,
                        color = if (failed) colors.statusError else colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Cancel",
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

@Composable
private fun PinEntryPopup(
    deviceName: String,
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
                "Pair with $deviceName",
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
                        "Enter the 6-digit PIN shown on the other device.",
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
                                "Verifying with the other device…",
                                fontSize = 13.sp,
                                color = colors.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else if (state.isWaitingForPin) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Waiting for the other device…",
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
