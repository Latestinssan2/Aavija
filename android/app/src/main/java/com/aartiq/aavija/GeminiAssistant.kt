package com.aartiq.aavija

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Snapshot of all devices, discovery radars, security, and gesture state.
 */
data class DeviceSnapshot(
    val isAdvertising: Boolean,
    val localPort: Int?,
    val isPaired: Boolean,
    val pairedMacName: String?,
    val pairedPhoneName: String?,
    val selectedMac: String?,
    val phoneTargetActive: Boolean,
    val discoveredMacs: List<MacDiscovery.MacDevice>,
    val discoveredPhones: List<PeerDiscovery.PhonePeer>,
    val pairedPeers: List<PairedPeer>,
    val isCameraRunning: Boolean,
    val cameraFps: Int,
    val activeMode: String,
    val lastPairingStatus: String?,
)

data class GeminiMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val quickActions: List<GeminiAction> = emptyList(),
)

data class GeminiAction(
    val title: String,
    val actionType: GeminiActionType,
    val target: String? = null,
)

enum class GeminiActionType {
    UNPAIR_MAC,
    UNPAIR_WINDOWS,
    UNPAIR_ALL,
    REFRESH_DEVICES,
    OPEN_RADAR,
    OPEN_GUIDE,
    TOGGLE_CAMERA,
}

/**
 * Google Gemini AI Assistant integration for Aavija.
 *
 * Provides real-time device intelligence, status reporting (Mac, Windows, Android),
 * interactive unpairing, deep link handling, and natural language explanations.
 */
class GeminiAssistant(
    private val context: Context,
    private val runtime: AavijaRuntime,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AavijaGemini"
        private const val PREFS_NAME = "aavija_gemini_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value?.trim()).apply()
        }

    private val _messages = MutableStateFlow<List<GeminiMessage>>(emptyList())
    val messages: StateFlow<List<GeminiMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // Welcome greeting with initial state & quick action suggestions
        postWelcomeMessage()
    }

    fun postWelcomeMessage() {
        val snapshot = runtime.getDeviceSnapshot()
        val connectedSummary = buildConnectedSummary(snapshot)

        val initialText = buildString {
            append("✨ **Google Gemini for Aavija**\n\n")
            append("I can help you monitor connected devices, unpair Macs or Windows PCs, explain mid-air gestures, and configure TLS 1.3 peer-to-peer security.\n\n")
            append("**Live Device Status:**\n")
            append(connectedSummary)
        }

        val actions = mutableListOf<GeminiAction>()
        if (snapshot.isPaired || snapshot.pairedMacName != null) {
            val isWin = snapshot.pairedMacName?.contains("Windows", ignoreCase = true) == true ||
                snapshot.pairedMacName?.contains("PC", ignoreCase = true) == true
            actions.add(
                GeminiAction(
                    title = if (isWin) "Unpair Windows PC ✕" else "Unpair Mac ✕",
                    actionType = if (isWin) GeminiActionType.UNPAIR_WINDOWS else GeminiActionType.UNPAIR_MAC,
                    target = snapshot.pairedMacName,
                )
            )
        }
        actions.add(GeminiAction(title = "Which devices are connected?", actionType = GeminiActionType.REFRESH_DEVICES))
        actions.add(GeminiAction(title = "How do gestures work?", actionType = GeminiActionType.OPEN_GUIDE))

        _messages.value = listOf(
            GeminiMessage(
                isUser = false,
                text = initialText,
                quickActions = actions,
            )
        )
    }

    fun ask(userPrompt: String) {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return

        val userMsg = GeminiMessage(isUser = true, text = trimmed)
        _messages.value = _messages.value + userMsg

        scope.launch {
            _isLoading.value = true
            try {
                val response = generateResponse(trimmed)
                _messages.value = _messages.value + response
            } catch (e: Exception) {
                Log.e(TAG, "Gemini query error", e)
                val fallback = generateLocalResponse(trimmed, runtime.getDeviceSnapshot())
                _messages.value = _messages.value + fallback
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun executeAction(action: GeminiAction, onActionComplete: (() -> Unit)? = null) {
        scope.launch {
            when (action.actionType) {
                GeminiActionType.UNPAIR_MAC -> {
                    val macName = action.target ?: runtime.advertiser.state.value.pairedMacName ?: "Mac"
                    runtime.unpairDevice(macName)
                    _messages.value = _messages.value + GeminiMessage(
                        isUser = false,
                        text = "✅ **Unpaired successfully!**\nRemoved pairing tokens for **$macName**. Local TLS 1.3 pinning session has been cleared.",
                        quickActions = listOf(
                            GeminiAction("Check connected devices", GeminiActionType.REFRESH_DEVICES)
                        ),
                    )
                }
                GeminiActionType.UNPAIR_WINDOWS -> {
                    val winName = action.target ?: runtime.advertiser.state.value.pairedMacName ?: "Windows PC"
                    runtime.unpairDevice(winName)
                    _messages.value = _messages.value + GeminiMessage(
                        isUser = false,
                        text = "✅ **Windows PC Unpaired!**\nRemoved pairing tokens for **$winName**. Ready to pair with a new device.",
                        quickActions = listOf(
                            GeminiAction("Check connected devices", GeminiActionType.REFRESH_DEVICES)
                        ),
                    )
                }
                GeminiActionType.UNPAIR_ALL -> {
                    runtime.unpairDevice(null)
                    _messages.value = _messages.value + GeminiMessage(
                        isUser = false,
                        text = "🗑️ **All Devices Unpaired!**\nCleared all macOS, Windows, and Android pairing credentials.",
                        quickActions = listOf(
                            GeminiAction("Scan for nearby devices", GeminiActionType.REFRESH_DEVICES)
                        ),
                    )
                }
                GeminiActionType.REFRESH_DEVICES -> {
                    val snapshot = runtime.getDeviceSnapshot()
                    val reply = buildDeviceDetailsResponse(snapshot)
                    _messages.value = _messages.value + reply
                }
                GeminiActionType.OPEN_GUIDE -> {
                    _messages.value = _messages.value + GeminiMessage(
                        isUser = false,
                        text = "📖 **Aavija Gestures & Connection Guide**\n\n" +
                            "• **✊ Grab Gesture**: Make a fist in front of the front camera to hold on to your current screen / file.\n" +
                            "• **🖐️ Release Gesture**: Open your palm towards the target screen to throw the held item across!\n" +
                            "• **TLS 1.3 Wi-Fi**: Peer-to-peer over your local network — no cloud servers, zero latency.",
                        quickActions = listOf(
                            GeminiAction("Check connected devices", GeminiActionType.REFRESH_DEVICES)
                        ),
                    )
                }
                GeminiActionType.OPEN_RADAR -> {
                    val snapshot = runtime.getDeviceSnapshot()
                    val reply = buildDeviceDetailsResponse(snapshot)
                    _messages.value = _messages.value + reply
                }
                GeminiActionType.TOGGLE_CAMERA -> {
                    val current = runtime.advertiser.state.value.gestureMode
                    runtime.advertiser.setGestureMode(!current)
                    _messages.value = _messages.value + GeminiMessage(
                        isUser = false,
                        text = if (!current) "📷 Gesture Camera activated!" else "🛑 Gesture Camera paused.",
                    )
                }
            }
            onActionComplete?.invoke()
        }
    }

    private suspend fun generateResponse(prompt: String): GeminiMessage = withContext(Dispatchers.IO) {
        val snapshot = runtime.getDeviceSnapshot()
        val key = apiKey

        // Check if intent is an unpair request:
        val lower = prompt.lowercase()
        if (lower.contains("unpair") || lower.contains("disconnect") || lower.contains("forget")) {
            return@withContext handleUnpairQuery(lower, snapshot)
        }

        // Check if intent is "which devices are connected"
        if (lower.contains("which device") || lower.contains("connected device") ||
            lower.contains("devices connected") || lower.contains("paired device") ||
            lower.contains("who is connected") || lower.contains("show device") || lower == "devices"
        ) {
            return@withContext buildDeviceDetailsResponse(snapshot)
        }

        // If user provided a Gemini API Key, try online Google Gemini REST API:
        if (!key.isNullOrBlank()) {
            try {
                val apiResponse = callGeminiApi(prompt, snapshot, key)
                if (apiResponse != null) {
                    val actions = buildDynamicActions(snapshot)
                    return@withContext GeminiMessage(isUser = false, text = apiResponse, quickActions = actions)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Online Gemini call failed, falling back to local engine", e)
            }
        }

        // Built-in intelligent context engine (100% offline, native, zero cloud delay):
        generateLocalResponse(prompt, snapshot)
    }

    private fun handleUnpairQuery(lower: String, snapshot: DeviceSnapshot): GeminiMessage {
        val isWindows = lower.contains("win") || lower.contains("pc")
        val isMac = lower.contains("mac") || lower.contains("apple") || lower.contains("macbook")
        val isPhone = lower.contains("phone") || lower.contains("android") || lower.contains("pixel")

        val actions = mutableListOf<GeminiAction>()
        val text = buildString {
            if (snapshot.isPaired || snapshot.pairedMacName != null || snapshot.pairedPeers.isNotEmpty()) {
                append("I can unpair your devices immediately:\n\n")
                if (snapshot.pairedMacName != null) {
                    val name = snapshot.pairedMacName
                    val isWinDevice = name.contains("Windows", ignoreCase = true) || name.contains("PC", ignoreCase = true)
                    append("• **${if (isWinDevice) "🪟 Windows PC" else "🍎 Mac"}**: $name\n")
                    actions.add(
                        GeminiAction(
                            title = "Unpair $name ✕",
                            actionType = if (isWinDevice) GeminiActionType.UNPAIR_WINDOWS else GeminiActionType.UNPAIR_MAC,
                            target = name,
                        )
                    )
                }
                snapshot.pairedPeers.forEach { peer ->
                    append("• **📱 Android Phone**: ${peer.name}\n")
                    actions.add(
                        GeminiAction(
                            title = "Unpair ${peer.name} ✕",
                            actionType = GeminiActionType.UNPAIR_ALL,
                            target = peer.id,
                        )
                    )
                }
                actions.add(GeminiAction("Unpair All Devices ✕", GeminiActionType.UNPAIR_ALL))
            } else {
                append("ℹ️ There are currently **no devices paired** to unpair.\n\n")
                append("Nearby devices discovered on your Wi-Fi:\n")
                if (snapshot.discoveredMacs.isEmpty() && snapshot.discoveredPhones.isEmpty()) {
                    append("• *No devices currently in range.* Ensure both devices are on the same Wi-Fi.")
                } else {
                    snapshot.discoveredMacs.forEach { mac ->
                        append("• ${if (mac.isWindows) "🪟 Windows" else "🍎 Mac"}: **${mac.name}**\n")
                    }
                    snapshot.discoveredPhones.forEach { p ->
                        append("• 📱 Android: **${p.name}**\n")
                    }
                }
                actions.add(GeminiAction("Scan Radar", GeminiActionType.REFRESH_DEVICES))
            }
        }

        return GeminiMessage(isUser = false, text = text, quickActions = actions)
    }

    private fun buildDeviceDetailsResponse(snapshot: DeviceSnapshot): GeminiMessage {
        val actions = mutableListOf<GeminiAction>()
        val text = buildString {
            append("📡 **Aavija Connected & Discovered Devices**\n\n")

            // Currently Paired / Active
            if (snapshot.isPaired || snapshot.pairedMacName != null || snapshot.pairedPhoneName != null) {
                append("**🟢 Currently Paired:**\n")
                if (snapshot.pairedMacName != null) {
                    val isWin = snapshot.pairedMacName.contains("Windows", ignoreCase = true) ||
                        snapshot.pairedMacName.contains("PC", ignoreCase = true)
                    append("• ${if (isWin) "🪟 Windows PC" else "🍎 Mac"}: **${snapshot.pairedMacName}**\n")
                    append("  - Security: TLS 1.3 Certificate Pinned\n")
                    append("  - Status: ${if (snapshot.selectedMac == snapshot.pairedMacName) "Active Target ⚡" else "Paired & Ready"}\n")
                    actions.add(
                        GeminiAction(
                            title = if (isWin) "Unpair Windows ✕" else "Unpair Mac ✕",
                            actionType = if (isWin) GeminiActionType.UNPAIR_WINDOWS else GeminiActionType.UNPAIR_MAC,
                            target = snapshot.pairedMacName,
                        )
                    )
                }
                if (snapshot.pairedPhoneName != null) {
                    append("• 📱 Android Phone: **${snapshot.pairedPhoneName}**\n")
                    append("  - Status: ${if (snapshot.phoneTargetActive) "Active Target ⚡" else "Paired"}\n")
                }
                append("\n")
            } else {
                append("**⚪ No Devices Currently Paired**\n")
                append("Tap any nearby device below to pair with a 6-digit PIN.\n\n")
            }

            // Nearby Discovered Devices on Radar
            append("**📡 Nearby Radar Devices (Same Wi-Fi):**\n")
            val totalDiscovered = snapshot.discoveredMacs.size + snapshot.discoveredPhones.size
            if (totalDiscovered == 0) {
                append("• *No active peers found.* Open Aavija on your Mac, Windows PC, or Android phone.\n")
            } else {
                snapshot.discoveredMacs.forEach { mac ->
                    val isWin = mac.isWindows || mac.name.contains("Windows", ignoreCase = true) || mac.name.contains("PC", ignoreCase = true)
                    val isPaired = snapshot.isPaired && snapshot.pairedMacName == mac.name
                    append("• ${if (isWin) "🪟 Windows" else "🍎 Mac"}: **${mac.name}** ${if (isPaired) "(Paired ✓)" else "(Available)"}\n")
                }
                snapshot.discoveredPhones.forEach { p ->
                    val isPaired = snapshot.pairedPeers.any { it.id == p.id } || snapshot.pairedPhoneName == p.name
                    append("• 📱 Android: **${p.name}** ${if (isPaired) "(Paired ✓)" else "(Available)"}\n")
                }
            }

            // Gesture Camera info
            append("\n**📷 Gesture Engine:**\n")
            append("• Camera: ${if (snapshot.isCameraRunning) "Active (Tracking Hands)" else "Idle / Background"}\n")
            append("• Mode: ${snapshot.activeMode}\n")
        }

        actions.add(GeminiAction("Refresh Radar 🔄", GeminiActionType.REFRESH_DEVICES))
        actions.add(GeminiAction("How do gestures work? 🖐️", GeminiActionType.OPEN_GUIDE))

        return GeminiMessage(isUser = false, text = text, quickActions = actions)
    }

    private fun generateLocalResponse(prompt: String, snapshot: DeviceSnapshot): GeminiMessage {
        val lower = prompt.lowercase()
        val actions = mutableListOf<GeminiAction>()

        val replyText = when {
            lower.contains("gesture") || lower.contains("how does") || lower.contains("work") || lower.contains("grab") || lower.contains("release") -> {
                actions.add(GeminiAction("Which devices are connected?", GeminiActionType.REFRESH_DEVICES))
                "🖐️ **How Aavija Gestures Work:**\n\n" +
                    "1. **Grab (✊ Fist)**: Form a fist toward your selfie camera. Aavija instantly grabs your screen or current document.\n" +
                    "2. **Aim**: Turn your hand toward your Mac, Windows PC, or Android phone.\n" +
                    "3. **Release (🖐️ Open Palm)**: Open your hand. Your file transfers in milliseconds over TLS 1.3 Wi-Fi!\n\n" +
                    "No cloud servers, no intermediaries. 100% peer-to-peer."
            }
            lower.contains("security") || lower.contains("tls") || lower.contains("pin") || lower.contains("safe") -> {
                "🛡️ **Aavija Security Architecture:**\n\n" +
                    "• **TLS 1.3 Mutual Pinning**: End-to-end encrypted connection between your phone and computer.\n" +
                    "• **6-Digit One-Time PIN**: Used solely during initial handshake to exchange ephemeral session tokens.\n" +
                    "• **5-Attempt Lockout**: Any brute force attempt triggers an automatic 5-minute cooldown.\n" +
                    "• **Zero Cloud**: Transfers occur entirely over your local Wi-Fi router."
            }
            lower.contains("windows") || lower.contains("pc") -> {
                actions.add(GeminiAction("Scan Radar", GeminiActionType.REFRESH_DEVICES))
                "🪟 **Connecting to Windows:**\n\n" +
                    "1. Launch Aavija for Windows on your PC.\n" +
                    "2. Ensure both your Android phone and Windows PC are on the same Wi-Fi network.\n" +
                    "3. Your PC will appear automatically in the radar list above.\n" +
                    "4. Tap it and enter the 6-digit PIN to establish encrypted connection."
            }
            lower.contains("mac") || lower.contains("apple") || lower.contains("macos") -> {
                actions.add(GeminiAction("Scan Radar", GeminiActionType.REFRESH_DEVICES))
                "🍎 **Connecting to macOS:**\n\n" +
                    "1. Launch Aavija macOS Menu Bar app.\n" +
                    "2. Make sure Wi-Fi is connected.\n" +
                    "3. Tap your Mac in the radar list, enter the 6-digit PIN, and start sharing!"
            }
            else -> {
                val connected = buildConnectedSummary(snapshot)
                actions.add(GeminiAction("Which devices are connected?", GeminiActionType.REFRESH_DEVICES))
                actions.add(GeminiAction("How do gestures work?", GeminiActionType.OPEN_GUIDE))
                "✨ **Gemini Assistant:**\n\n" +
                    "I am here to assist with Aavija device pairing, unpairing, and gestures.\n\n" +
                    "**Current Status:**\n$connected\n\n" +
                    "Feel free to ask: *\"Which devices are connected?\"*, *\"Unpair my Mac\"*, or *\"How do I use gestures?\"*"
            }
        }

        return GeminiMessage(isUser = false, text = replyText, quickActions = actions)
    }

    private fun buildConnectedSummary(snapshot: DeviceSnapshot): String {
        return buildString {
            if (snapshot.pairedMacName != null) {
                val isWin = snapshot.pairedMacName.contains("Windows", ignoreCase = true) ||
                    snapshot.pairedMacName.contains("PC", ignoreCase = true)
                append("• Connected: ${if (isWin) "🪟" else "🍎"} **${snapshot.pairedMacName}** (TLS 1.3 Active)\n")
            } else if (snapshot.pairedPhoneName != null) {
                append("• Connected: 📱 **${snapshot.pairedPhoneName}** (Android)\n")
            } else {
                append("• No devices paired yet\n")
            }
            val countNearby = snapshot.discoveredMacs.size + snapshot.discoveredPhones.size
            append("• Radar: $countNearby nearby device(s) visible\n")
            append("• Camera: ${if (snapshot.isCameraRunning) "🟢 Hand tracking active" else "⚪ Idle"}")
        }
    }

    private fun buildDynamicActions(snapshot: DeviceSnapshot): List<GeminiAction> {
        val list = mutableListOf<GeminiAction>()
        if (snapshot.isPaired || snapshot.pairedMacName != null) {
            val isWin = snapshot.pairedMacName?.contains("Windows", ignoreCase = true) == true ||
                snapshot.pairedMacName?.contains("PC", ignoreCase = true) == true
            list.add(
                GeminiAction(
                    title = if (isWin) "Unpair Windows ✕" else "Unpair Mac ✕",
                    actionType = if (isWin) GeminiActionType.UNPAIR_WINDOWS else GeminiActionType.UNPAIR_MAC,
                    target = snapshot.pairedMacName,
                )
            )
        }
        list.add(GeminiAction("Which devices are connected?", GeminiActionType.REFRESH_DEVICES))
        return list
    }

    private fun callGeminiApi(prompt: String, snapshot: DeviceSnapshot, key: String): String? {
        val url = URL("$GEMINI_ENDPOINT?key=$key")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.doOutput = true

        val systemContext = "You are Gemini Assistant inside the Aavija native app (crafted by Preet Patel). " +
            "Aavija is a peer-to-peer Wi-Fi gesture-sharing app using MediaPipe hand tracking (Grab to hold screenshot/file, Release to throw) and TLS 1.3 encryption. " +
            "Live system status: " +
            "Paired Mac/PC: ${snapshot.pairedMacName ?: "None"}, " +
            "Paired Phone: ${snapshot.pairedPhoneName ?: "None"}, " +
            "Discovered Macs/PCs: ${snapshot.discoveredMacs.map { it.name }}, " +
            "Discovered Phones: ${snapshot.discoveredPhones.map { it.name }}, " +
            "Camera Running: ${snapshot.isCameraRunning}. " +
            "Answer concisely and informatively. If asked about connected devices, list the paired Mac/PC and nearby radar devices accurately. If asked to unpair, inform the user they can tap the unpair button."

        val requestBody = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", "$systemContext\n\nUser request: $prompt")
                            )
                        )
                    }
                )
            )
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
            os.write(requestBody.toString())
            os.flush()
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val json = JSONObject(response)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val first = candidates.getJSONObject(0)
                val content = first.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text")
                }
            }
        }
        return null
    }
}
