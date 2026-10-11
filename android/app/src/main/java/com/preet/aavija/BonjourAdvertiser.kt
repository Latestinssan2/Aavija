package com.preet.aavija

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.ServerSocket

/**
 * Makes this phone discoverable on the local Wi-Fi as an Aavija service, and
 * runs the pairing handshake when a Mac connects.
 *
 * We swapped who is the "server" (originally the Mac): the Mac's NWListener is
 * broken on the user's machine, but Android's networking is fine — so the phone
 * is now the listener. Two pieces work together:
 *
 *  - ServerSocket (TLS): a real TCP port that stays open, waiting for the Mac
 *    to connect. We advertise this exact port so the Mac knows where to dial in.
 *  - NsdManager: Android's Bonjour/mDNS API (called "Network Service Discovery").
 *    It announces "_gestureshare._tcp" + our port on the Wi-Fi so the Mac's
 *    NWBrowser can find us without anyone typing an IP address.
 *
 * ### Phase 3b — the pairing handshake
 * When a Mac connects we don't just take its word for it. We send a challenge
 * (a random salt), the Mac answers with a proof that it knows the 6-digit PIN
 * (bound to the certificate fingerprint it saw on the wire), and we verify that
 * proof against the PIN currently typed on this phone's screen. A man-in-the-
 * middle that swapped in its own certificate would produce a proof bound to a
 * DIFFERENT fingerprint and be rejected. On success the Mac gets a session
 * token so it never needs the PIN again; 5 wrong PINs lock pairing out.
 */
class BonjourAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "AavijaAdvertiser"
        /** Hard rule of the project: this exact service type, nothing else. */
        const val SERVICE_TYPE = "_gestureshare._tcp"
    }

    /** One small object describing what the UI should show right now. */
    data class AdState(
        val isAdvertising: Boolean = false,
        val serviceName: String? = null,
        val port: Int? = null,
        val error: String? = null,
        val lastMessage: String? = null, // what the Mac last sent us (Phase 2)
        val lastPairing: String? = null, // human-readable pairing status (3b)
        val isPaired: Boolean = false,   // has a Mac successfully paired? (3b)
        val isWaitingForPin: Boolean = false, // a Mac is pairing; PIN not typed yet
    )

    private val _state = MutableStateFlow(AdState())
    val state: StateFlow<AdState> = _state.asStateFlow()

    /**
     * The PIN currently typed on the phone's screen. The Mac's proof is checked
     * against THIS value at the moment a connection arrives — so the user types
     * the PIN the Mac is showing, and the very next connection verifies it.
     */
    private val _typedPin = MutableStateFlow("")
    val typedPin: StateFlow<String> = _typedPin.asStateFlow()

    /** Called by the UI every time the PIN field changes. */
    fun setPin(pin: String) {
        // Only ever accept up to 6 digits — anything else is dropped so a stray
        // letter can't make the phone reject a correct PIN for the wrong reason.
        _typedPin.value = pin.filter { it.isDigit() }.take(6)
    }

    /**
     * Where pairing state (attempt count, lockout deadline, paired tokens)
     * lives. Backed by SharedPreferences so a force-quit can't reset the
     * lockout and a paired Mac stays paired across restarts.
     */
    private val pairingStore = PrefsPairingStore(context)

    /**
     * THIS phone's own certificate fingerprint — the value every pairing proof
     * is bound to. Computed once; it's just the SHA-256 of our self-signed cert.
     */
    private val phoneFingerprint: String by lazy { TlsIdentity.fingerprint() }

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Open the port and announce it. Safe to call once from onCreate. */
    fun start() {
        if (_state.value.isAdvertising) return

        try {
            // 1) Open a TLS-encrypted TCP port (Phase 3a). Port 0 = "OS, pick
            //    any free port." The phone is the TLS *server*, so it presents
            //    the self-signed certificate minted in TlsIdentity.
            val socket = TlsIdentity.createSslServerSocket()
            serverSocket = socket
            val port = socket.localPort

            // 2) Hold a MulticastLock so Android actually lets mDNS traffic in.
            acquireMulticastLock()

            // 3) Build the Bonjour service record: our type + the port above.
            val serviceName = Build.MODEL ?: "Android"
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = SERVICE_TYPE
                this.port = port
            }

            // 4) Register it. The listener tells us if it worked.
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Registered as '${info.serviceName}' on port $port")
                    _state.value = _state.value.copy(
                        isAdvertising = true,
                        serviceName = info.serviceName,
                        port = port,
                    )
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Registration failed: code $errorCode")
                    _state.value = _state.value.copy(error = "Bonjour registration failed (code $errorCode)")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "Unregistered '${info.serviceName}'")
                    _state.value = AdState()
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Unregistration failed: code $errorCode")
                }
            }
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

            // 5) Accept loop that runs the pairing handshake on each connection.
            startAcceptLoop(socket)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertiser", e)
            _state.value = _state.value.copy(error = e.message ?: "Unknown error")
        }
    }

    /** Stop advertising and release the port. Safe to call from onDestroy. */
    fun stop() {
        registrationListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        releaseMulticastLock()
        _state.value = AdState()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("aavija-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // Non-fatal: advertising can still work on some devices without it.
            Log.w(TAG, "Could not acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /**
     * Background thread that accepts incoming connections and runs the pairing
     * handshake (Phase 3b) over the TLS tunnel.
     *
     * Each connection is wrapped in its OWN try/catch: with a TLS server, a
     * random port-scanner or plaintext client will fail the handshake, and we
     * must shrug that off and keep serving rather than dying.
     */
    private fun startAcceptLoop(socket: ServerSocket) {
        Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { conn ->
                        val peer = conn.inetAddress?.hostAddress
                        Log.i(TAG, "Connection accepted from $peer")
                        handlePairingConnection(conn)
                    }
                } catch (e: Exception) {
                    // Socket closed during stop() — expected, just stop looping.
                    if (socket.isClosed) break
                    // Otherwise: a client that failed the TLS handshake (or sent
                    // garbage). Log it and keep accepting the next connection.
                    Log.w(TAG, "Dropping connection: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true // don't block the app from exiting
            start()
        }
    }

    /**
     * Speak one round of the pairing protocol with a freshly-connected Mac:
     * send a challenge, read its answer, verify, reply, and tell the UI what
     * happened. The connection is closed by the caller's `use { }` when this
     * returns — pairing is its own short-lived exchange.
     */
    private fun handlePairingConnection(conn: java.net.Socket) {
        val reader = conn.getInputStream().bufferedReader()
        val writer = conn.getOutputStream().bufferedWriter()

        // A FRESH salt every connection = replay protection: a proof captured
        // on one attempt is useless against the next challenge.
        val salt = PairingCrypto.randomToken()
        val protocol = PairingProtocol(salt, phoneFingerprint, pairingStore) { _typedPin.value }

        // 1) Send the challenge so the Mac can build its proof.
        writer.write(protocol.challengeLine() + "\n")
        writer.flush()

        // 2) Read the Mac's answer (a single line: "pair" on first contact,
        //    "auth" if it's returning with a token).
        val line = reader.readLine()
        if (line.isNullOrBlank()) {
            Log.w(TAG, "Connection sent no pairing message")
            return
        }
        Log.i(TAG, "Pairing message: $line")

        // 3) Verify and reply.
        val reply = protocol.handle(line)
        writer.write(reply + "\n")
        writer.flush()
        Log.i(TAG, "Pairing reply: $reply")

        // 4) Publish the outcome so the phone's screen can show it.
        publishPairingOutcome(reply)
    }

    /**
     * Turn a protocol reply line into a status the UI can display.
     *
     * A "no pin entered" rejection is NOT a real failure — it just means the
     * Mac is pairing but nobody has typed the PIN on this phone yet. We flag
     * that as "waiting" so the screen shows a calm hint, not a red error.
     */
    private fun publishPairingOutcome(reply: String) {
        val obj = try { JSONObject(reply) } catch (_: Exception) { return }
        val type = obj.optString("type")
        val reason = obj.optString("reason")

        val waiting = (type == "rejected" && reason == "no pin entered")

        val (status, paired) = when {
            type == "paired" -> "Paired successfully" to true
            type == "authenticated" -> "Mac reconnected (token accepted)" to true
            type == "locked" -> "Too many wrong PINs — locked out" to false
            waiting -> "A Mac is pairing — type the PIN it shows" to false
            type == "rejected" -> "Rejected: $reason" to false
            else -> return
        }
        _state.value = _state.value.copy(
            lastPairing = status,
            isPaired = paired,
            isWaitingForPin = waiting,
        )
    }
}
