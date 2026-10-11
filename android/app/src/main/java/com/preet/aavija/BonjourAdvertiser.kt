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
import java.net.ServerSocket

/**
 * Makes this phone discoverable on the local Wi-Fi as an Aavija service.
 *
 * We swapped who is the "server" (originally the Mac): the Mac's NWListener is
 * broken on the user's machine, but Android's networking is fine — so the phone
 * is now the listener. Two pieces work together:
 *
 *  - ServerSocket: a real TCP port that stays open, waiting for the Mac to
 *    connect. We advertise this exact port so the Mac knows where to dial in.
 *  - NsdManager: Android's Bonjour/mDNS API (called "Network Service Discovery").
 *    It announces "_gestureshare._tcp" + our port on the Wi-Fi so the Mac's
 *    NWBrowser can find us without anyone typing an IP address.
 *
 * Nothing about file transfer happens in Phase 1c — this class only opens the
 * door and rings the doorbell (advertises). Transfers come in a later phase.
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
    )

    private val _state = MutableStateFlow(AdState())
    val state: StateFlow<AdState> = _state.asStateFlow()

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
                    _state.value = AdState(
                        isAdvertising = true,
                        serviceName = info.serviceName,
                        port = port,
                    )
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Registration failed: code $errorCode")
                    _state.value = AdState(error = "Bonjour registration failed (code $errorCode)")
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

            // 5) Accept-and-close loop so the port doesn't fill with stale
            //    connections. Real file transfer wiring comes in a later phase.
            startAcceptLoop(socket)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertiser", e)
            _state.value = AdState(error = e.message ?: "Unknown error")
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
     * Background thread that accepts incoming connections and reads a single
     * line of text from each (Phase 3a = a "hello" from the Mac over TLS).
     * The message is stored in [AdState.lastMessage] so the UI can show it.
     *
     * Each connection is wrapped in its OWN try/catch: with a TLS server, a
     * random port-scanner or plaintext client will fail the handshake, and we
     * must shrug that off and keep serving rather than dying.
     *
     * The full file-transfer protocol (length-prefixed header + raw bytes)
     * comes in a later phase; for now we just prove the encrypted pipe is open.
     */
    private fun startAcceptLoop(socket: ServerSocket) {
        Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { conn ->
                        Log.i(TAG, "Connection accepted from ${conn.inetAddress?.hostAddress}")
                        // Read one line (up to '\n'). This is naive — a later
                        // phase replaces it with the real framed protocol.
                        // For TLS this first read triggers the handshake; if it
                        // fails, we land in the catch below, not in the server.
                        val reader = conn.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        if (!line.isNullOrBlank()) {
                            Log.i(TAG, "Received: $line")
                            _state.value = _state.value.copy(lastMessage = line)
                        }
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
}
