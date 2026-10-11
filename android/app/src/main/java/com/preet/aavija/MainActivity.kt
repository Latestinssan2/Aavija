package com.preet.aavija

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1c of Aavija (swapped architecture):
 * the Android phone is the *listener* — it opens a TCP port and advertises it
 * over Bonjour so the Mac can find it. This screen just shows whether that
 * advertising is live; the Mac side does the discovering.
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
            "Open the Aavija menu on your Mac to see this phone.",
            fontSize = 13.sp,
            color = Color.Gray,
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
