package com.preet.aavija

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Phase 1c of Aavija (swapped architecture):
 * the Android phone is the *listener* — it opens a TCP port and advertises it
 * over Bonjour so the Mac can find it. This screen shows whether that
 * advertising is live; the Mac side does the discovering.
 *
 * Phase 3b added the PIN field: when a Mac wants to pair, the Mac shows a
 * 6-digit PIN and you type it here. The phone then checks the Mac's proof
 * against whatever is in this box.
 */
class MainActivity : ComponentActivity() {

    private lateinit var advertiser: BonjourAdvertiser
    private lateinit var macDiscovery: MacDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advertiser = BonjourAdvertiser(this)
        macDiscovery = MacDiscovery(this)
        requestLegacyStoragePermissionIfNeeded()

        setContent {
            MaterialTheme {
                AdvertiserScreen(advertiser, macDiscovery)
            }
        }
    }

    /**
     * On Android 9 and older, saving a received file into the public Downloads
     * folder needs WRITE_EXTERNAL_STORAGE. Android 10+ goes through MediaStore
     * and needs no permission — and the permission isn't even declared on those
     * versions (maxSdkVersion=28), so we only ever ask on the old ones.
     */
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    override fun onStart() {
        super.onStart()
        advertiser.start()
        macDiscovery.start() // Phase 5a: start filling the Mac list immediately
    }

    override fun onStop() {
        macDiscovery.stop()
        advertiser.stop()
        super.onStop()
    }
}

@Composable
fun AdvertiserScreen(advertiser: BonjourAdvertiser, macDiscovery: MacDiscovery) {
    val state by advertiser.state.collectAsState()
    val pin by advertiser.typedPin.collectAsState()
    val macs by macDiscovery.macs.collectAsState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // Phase 5b: a wrong PIN should be FELT, not just read. wrongPinCount goes
    // up by one per wrong guess, so buzzing on each change buzzes once per
    // attempt. (LaunchedEffect re-runs when its key changes — that's the
    // whole trick here.)
    LaunchedEffect(state.wrongPinCount) {
        if (state.wrongPinCount > 0) buzzWrongPin(context)
    }

    // The PIN popup only exists once a device is picked. Tapping a row calls
    // selectMac, which sets state.selectedMac — that's what opens this.
    val selectedMac = state.selectedMac
    if (selectedMac != null && !state.isPaired) {
        PinEntryPopup(
            macName = selectedMac,
            pin = pin,
            state = state,
            onPinChange = { advertiser.setPin(it) },
            onSubmit = {
                advertiser.submitPin()          // arms exactly one verification
                focusManager.clearFocus()       // dismiss the keyboard
            },
            onDismiss = { advertiser.selectMac(null) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Aavija", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Gesture file sharing", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))

        if (state.error != null) {
            StatusDot(Color.Red)
            Spacer(Modifier.height(8.dp))
            Text("Error: ${state.error}", color = Color.Red)
        } else if (state.isAdvertising) {
            StatusDot(Color(0xFF4CAF50)) // green
            Spacer(Modifier.height(8.dp))
            Text("Discoverable on this Wi-Fi", fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Name: ${state.serviceName}", fontSize = 14.sp, color = Color.Gray)
            Text("Port: ${state.port}", fontSize = 14.sp, color = Color.Gray)
        } else {
            StatusDot(Color(0xFFFFC107)) // amber
            Spacer(Modifier.height(8.dp))
            Text("Starting discovery…", fontSize = 18.sp)
        }

        // Phase 5b: tap a Mac to pick it. The list is sorted by name and rows
        // are keyed by id, so it does NOT reshuffle when another device
        // appears — which is what stops your finger landing on the wrong one.
        Spacer(Modifier.height(24.dp))
        Text("Available Macs", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        if (macs.isEmpty()) {
            Text(
                "Searching… make sure Aavija is running on your Mac.",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                macs.forEach { mac ->
                    DeviceRow(
                        name = mac.name,
                        isSelected = mac.name == selectedMac,
                        onClick = { advertiser.selectMac(mac.name) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Phase 3b: show the result of the last pairing attempt.
        if (state.lastPairing != null) {
            Spacer(Modifier.height(16.dp))
            // Amber + "waiting" while a Mac pairs but the PIN isn't typed yet;
            // green once paired; red only for a real rejection or lockout.
            val statusColor = when {
                state.isPaired -> Color(0xFF4CAF50)               // green
                state.isWaitingForPin -> Color(0xFFFF9800)        // amber = waiting
                else -> Color(0xFFF44336)                         // red = rejected/locked
            }
            StatusDot(statusColor)
            Spacer(Modifier.height(4.dp))
            Text(state.lastPairing!!, fontSize = 14.sp, color = statusColor)
        }

        // Phase 4a: a file arriving (or the result of the last one).
        if (state.transferStatus != null) {
            Spacer(Modifier.height(16.dp))
            val transferColor = when {
                state.isReceiving -> Color(0xFFFF9800)     // amber = in progress
                state.transferFailed -> Color(0xFFF44336)  // red = failed/deleted
                else -> Color(0xFF4CAF50)                  // green = saved
            }
            StatusDot(transferColor)
            Spacer(Modifier.height(4.dp))
            Text(
                state.transferStatus!!,
                fontSize = 14.sp,
                color = transferColor,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            when {
                state.isPaired && state.pairedMacName != null ->
                    "This phone is paired with ${state.pairedMacName}."
                state.isPaired ->
                    "This phone is paired with your Mac."
                selectedMac != null ->
                    "Tap to change device, or wait for the PIN popup."
                else ->
                    "Tap a Mac above to pair with it."
            },
            fontSize = 13.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )

        // Phase 4b: pick a file and queue it for the paired Mac. The Mac pulls
        // it on its next heartbeat, so this button only stages the file.
        //
        // Hashing reads the WHOLE file, so it must never run on the main thread —
        // a 100 MB clip would freeze the UI for seconds. The picker callback
        // therefore only opens a coroutine and does the work on Dispatchers.IO.
        val scope = rememberCoroutineScope()
        val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers don't grant persistable access; the read below
                // still works while the app lives, and the Mac pulls promptly.
            }
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
            Spacer(Modifier.height(16.dp))
            Button(onClick = { pickLauncher.launch(arrayOf("*/*")) }) {
                Text("Send file to Mac…")
            }
        }

        // ---- Phase 5: gesture mode (OFF by default) -------------------------
        // The switch is the ONLY thing that starts the camera. Nothing is
        // recorded, nothing is uploaded: frames become 21 points, the points
        // become GRAB/RELEASE, and the frame is dropped.
        Spacer(Modifier.height(28.dp))

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
        LaunchedEffect(gestureMode) {
            if (gestureMode && !cameraGranted) {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Gesture mode", fontSize = 16.sp)
                Text("Camera runs only while this is on", fontSize = 12.sp, color = Color.Gray)
            }
            Switch(
                checked = gestureMode,
                onCheckedChange = { on ->
                    cameraProblem = null
                    gestureMode = on
                },
            )
        }

        if (gestureMode && cameraGranted) {
            Spacer(Modifier.height(12.dp))
            var pose by remember { mutableStateOf(HandPose.UNKNOWN) }
            var lastEvent by remember { mutableStateOf<String?>(null) }
            GestureCameraPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
                onPose = { pose = it },
                onEvent = { event ->
                    lastEvent = when (event) {
                        HandGestureMachine.EventType.GRAB -> "GRAB ✊"
                        HandGestureMachine.EventType.RELEASE -> "RELEASE 🖐"
                    }
                },
                onError = { cameraProblem = it },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (pose) {
                    HandPose.OPEN -> "Open hand — ready to grab"
                    HandPose.FIST -> "Fist — holding"
                    HandPose.UNKNOWN -> "Show your hand to the camera"
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            lastEvent?.let {
                Text(
                    "Last gesture: $it",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        cameraProblem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = Color(0xFFF44336), textAlign = TextAlign.Center)
        }
    }
}

/**
 * Read (display name, size, SHA-256) from a picked document in one pass.
 * Returns null when the provider is gone or the file has no size — callers show
 * a message rather than queueing a transfer that can never be verified.
 */
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

/**
 * Phase 5b: the popup that appears the moment you tap a Mac in the list.
 *
 * It shows the PIN field, how many attempts you have left, and — once you're
 * locked out — a countdown that actually ticks. Submit is the ONLY thing that
 * lets the phone check a PIN, so a wrong guess can no longer be burned for you
 * by the Mac's own retry loop.
 */
@Composable
private fun PinEntryPopup(
    macName: String,
    pin: String,
    state: BonjourAdvertiser.AdState,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A ticking clock. Recomputed every second while a lockout is running, so
    // "4:59, 4:58, …" is a real countdown rather than a frozen number.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lockedUntilMs) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= state.lockedUntilMs) break
            delay(1_000)
        }
    }
    val lockRemainingMs = (state.lockedUntilMs - now).coerceAtLeast(0)
    val locked = lockRemainingMs > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $macName") },
        text = {
            Column {
                if (locked) {
                    Text(
                        "Too many wrong PINs. Try again in " +
                            "${lockRemainingMs / 60_000}m " +
                            "${(lockRemainingMs % 60_000) / 1_000}s",
                        color = Color(0xFFF44336),
                    )
                } else {
                    Text(
                        "Type the 6-digit PIN your Mac is showing.",
                        fontSize = 14.sp,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = onPinChange,
                        modifier = Modifier.width(160.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            letterSpacing = 6.sp,
                        ),
                    )
                    if (state.attemptsRemaining < PinPairing.MAX_ATTEMPTS) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${state.attemptsRemaining} of ${PinPairing.MAX_ATTEMPTS} attempts left",
                            fontSize = 13.sp,
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (state.lastRejection != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.lastRejection!!,
                            fontSize = 13.sp,
                            color = Color(0xFFF44336),
                        )
                    }
                    if (state.isWaitingForPin) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Waiting for your Mac…",
                            fontSize = 13.sp,
                            color = Color(0xFFFF9800),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !locked && pin.length == 6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3),
                    disabledContainerColor = Color(0xFFBDBDBD),
                ),
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * One short buzz, so a wrong PIN is felt as well as read.
 *
 * Android moved to a multi-vibrator API in 12 (VibratorManager) and kept the
 * old single one for everything below, so we ask for whichever exists. Every
 * API used here is real; `hasVibrator()` is checked because some phones (and
 * every emulator) simply have no motor.
 */
private fun buzzWrongPin(context: Context) {
    try {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            (manager as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") // pre-12 path; VIBRATOR_SERVICE still works
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE),
        )
    } catch (e: Exception) {
        // A phone that refuses to buzz shouldn't take the whole screen down.
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .padding(0.dp),
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.fillMaxSize(),
        ) {}
    }
}

/**
 * One row in the "Available Macs" list: a laptop glyph + the Mac's real name.
 * Tapping it picks that device — the choice is sticky (stored by name, not by
 * position in the list), so a new device appearing can't silently swap it.
 *
 * The NAME is genuinely read from the Mac (`Host.current().localizedName`) and
 * broadcast over mDNS, so it's authentic. The ICON is not — and can't be.
 * There is no protocol that returns another machine's desktop picture or file
 * icon; mDNS carries only a name, a service type and a port. So we draw a
 * neutral laptop in Compose instead. It scales crisply at any size and needs
 * no bundled image asset.
 */
@Composable
private fun DeviceRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaptopIcon()
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            // A plain tick, drawn as text — no icon dependency, and it reads
            // as "chosen" even for someone who has never seen this app.
            Text("✓", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A small laptop, drawn with two shapes: a screen (rounded rect) and a base
 * (a slightly wider bar under it). Pure geometry — no bitmap, so it stays
 * sharp at any density.
 */
@Composable
private fun LaptopIcon() {
    val tint = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Screen
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 17.dp)
                .background(tint, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
        )
        Spacer(Modifier.height(2.dp))
        // Base / keyboard deck, a touch wider than the screen.
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 3.dp)
                .background(tint, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
        )
    }
}
