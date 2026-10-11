package com.preet.aavija

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        advertiser = BonjourAdvertiser(this)

        setContent {
            MaterialTheme {
                AdvertiserScreen(advertiser)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        advertiser.start()
    }

    override fun onStop() {
        advertiser.stop()
        super.onStop()
    }
}

@Composable
fun AdvertiserScreen(advertiser: BonjourAdvertiser) {
    val state by advertiser.state.collectAsState()
    val pin by advertiser.typedPin.collectAsState()

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

        // Phase 3b: PIN entry. Type the 6 digits the Mac is showing.
        Spacer(Modifier.height(28.dp))
        Text("Pairing PIN", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { advertiser.setPin(it) },
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
