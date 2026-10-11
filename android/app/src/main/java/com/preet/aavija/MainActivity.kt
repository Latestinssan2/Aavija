package com.preet.aavija

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

        setContent {
            MaterialTheme {
                AdvertiserScreen(advertiser, macDiscovery)
            }
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

    // True once the user has tapped Submit with a full 6-digit PIN. It only
    // drives the "waiting" note — the PIN itself lives in the advertiser.
    var pinSubmitted by remember { mutableStateOf(false) }

    // A real outcome from the Mac (paired / wrong PIN / locked out) replaces
    // the waiting note, so drop it and let the status line take over.
    LaunchedEffect(state.lastPairing, state.isPaired) {
        if (state.isPaired || (state.lastPairing != null && !state.isWaitingForPin)) {
            pinSubmitted = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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

        // Phase 5a: Macs this phone can see on the Wi-Fi. Display only for
        // now — tapping/selecting a Mac and filtering on it lands in 5b.
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
                    DeviceRow(name = mac.name)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // Phase 3b: PIN entry. Type the 6 digits the Mac is showing.
        Spacer(Modifier.height(28.dp))
        Text("Pairing PIN", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = {
                pinSubmitted = false // editing invalidates the confirmation
                advertiser.setPin(it)
            },
            modifier = Modifier.width(160.dp),
            singleLine = true,
            enabled = !state.isPaired, // no need to type once paired
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 6.sp,
            ),
        )

        // Submit: confirm the PIN is typed. This can't push the PIN to the Mac
        // (the phone only listens — it never dials out), so all it really does
        // is hide the keyboard and show "waiting". The Mac is already retrying
        // every ~1.5s and will read the PIN on its next attempt. We still want
        // the button because tapping the field's "done" key on some keyboards
        // just dismisses the keyboard with no visible confirmation.
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                pinSubmitted = true
                focusManager.clearFocus() // dismisses the keyboard
            },
            enabled = pin.length == 6 && !state.isPaired,
            modifier = Modifier.width(160.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2196F3),
                disabledContainerColor = Color(0xFFBDBDBD),
            ),
        ) {
            Text("Submit", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        // Shown after Submit until the Mac's next retry reports back.
        if (pinSubmitted && !state.isPaired) {
            Spacer(Modifier.height(8.dp))
            Text(
                "PIN entered — your Mac will check it in a moment…",
                fontSize = 13.sp,
                color = Color(0xFFFF9800),
                textAlign = TextAlign.Center,
            )
        }

        // Phase 3b: show the result of the last pairing attempt.
        if (state.lastPairing != null) {
            Spacer(Modifier.height(12.dp))
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

        // Phase 2: show the last message the Mac sent us, once one arrives.
        if (state.lastMessage != null) {
            Spacer(Modifier.height(24.dp))
            StatusDot(Color(0xFF2196F3)) // blue = "we heard something"
            Spacer(Modifier.height(8.dp))
            Text("Mac says:", fontSize = 13.sp, color = Color.Gray)
            Text(state.lastMessage!!, fontSize = 16.sp)
        }

        Spacer(Modifier.height(40.dp))
        Text(
            if (state.isPaired)
                "This phone is paired with your Mac."
            else
                "Open the Aavija menu on your Mac to pair.",
            fontSize = 13.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
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
 *
 * The NAME is genuinely read from the Mac (`Host.current().localizedName`) and
 * broadcast over mDNS, so it's authentic. The ICON is not — and can't be.
 * There is no protocol that returns another machine's desktop picture or file
 * icon; mDNS carries only a name, a service type and a port. So we draw a
 * neutral laptop in Compose instead. It scales crisply at any size and needs
 * no bundled image asset.
 */
@Composable
private fun DeviceRow(name: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaptopIcon()
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
