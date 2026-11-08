package com.aartiq.aavija

import android.content.ContentValues
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
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

        /** Remembers the phone that paired with us, so the label survives a pause. */
        private const val DISPLAY_PREFS = "aavija_display"
        private const val KEY_PAIRED_PHONE = "paired_phone"

        /**
         * The accept loop serves one connection at a time. Without a read
         * timeout, a client that connects and never sends a line (or dies
         * mid-exchange) would wedge every later heartbeat and pull forever.
         */
        private const val SOCKET_READ_TIMEOUT_MS = 20_000

        /** Project rule: no transfer may exceed this, including screenshots. */
        private const val MAX_FILE_BYTES = 200L * 1024L * 1024L
    }

    /** One small object describing what the UI should show right now. */
    data class AdState(
        val isAdvertising: Boolean = false,
        val serviceName: String? = null,
        val port: Int? = null,
        val error: String? = null,
        val lastPairing: String? = null, // human-readable pairing status (3b)
        val isPaired: Boolean = false,   // has a Mac successfully paired? (3b)
        /** The Mac we paired with, remembered across restarts. Null = unknown. */
        val pairedMacName: String? = null,
        val isWaitingForPin: Boolean = false, // a Mac is pairing; PIN not typed yet
        /** The Mac the user tapped in the list. Null = nothing picked yet. */
        val selectedMac: String? = null,          // Phase 5b
        /**
         * A PIN prompt is genuinely live for [selectedMac].
         *
         * Needed because a phone that already paired with a Mac still has
         * `isPaired == true`; a NEW phone pairing request must be able to show
         * its PIN prompt without pretending that old pairing disappeared.
         */
        val pairingRequestActive: Boolean = false,
        /**
         * The phone that most recently paired with us as a CLIENT.
         *
         * Kept apart from [pairedMacName] because the pairing store has one
         * "last device" name: an Android pairing must not make an existing
         * paired Mac look untrusted in the Mac list.
         */
        val pairedPhoneName: String? = null,
        /**
         * True when that phone currently owns the single outgoing queue.
         *
         * Kept separate from [pairedPhoneName]: picking a Mac changes the send
         * target, but the phone is still trusted (and may still push to us).
         */
        val phoneTargetActive: Boolean = false,
        /** PIN guesses left before a lockout. Shown so 5 is never a surprise. */
        val attemptsRemaining: Int = PinPairing.MAX_ATTEMPTS, // Phase 5b
        /** Absolute time (ms) when a lockout ends. 0 = not locked out. */
        val lockedUntilMs: Long = 0,              // Phase 5b
        /** Increments on every wrong PIN — the UI buzzes the phone on each. */
        val wrongPinCount: Int = 0,               // Phase 5b
        /**
         * The last REAL failure ("Wrong PIN", "Locked out"), kept separately
         * from [lastPairing] so the Mac's polite "waiting…" updates can't wipe
         * it off the screen before you've read it.
         */
        val lastRejection: String? = null,        // Phase 5b
        /** Phase 4a: last file received (or why it failed). Null = none yet. */
        val transferStatus: String? = null,
        /** Phase 4a: a file is being received right now. */
        val isReceiving: Boolean = false,
        /** Phase 4b/7: a queued file is being sent to the Mac right now. */
        val isSending: Boolean = false,
        /** Phase 4a: the last transfer FAILED (so the UI can show red). */
        val transferFailed: Boolean = false,
        /** Phase 7: Gesture mode is ON, so queued files wait for a gesture. */
        val gestureMode: Boolean = false,
        /** Phase 7: the last gesture this phone detected ("GRAB ✊" / "RELEASE 🖐"). */
        val lastGesture: String? = null,
        /** True when a user-selected file is held in hand (grabbed) before releasing. */
        val isStagedFileGrabbed: Boolean = false,
        /** Name of user-selected file waiting in outgoing queue. */
        val stagedFileName: String? = null,
        /**
         * Screenshot sharing: the most recently received image, ready to open.
         *
         * [eventId] increases with every image so the UI can tell "a NEW photo
         * arrived" from a recomposition showing the same one. Non-images leave
         * this untouched.
         */
        val receivedImage: ReceivedImage? = null,
    )

    /** A saved image that the phone can open immediately after receiving it. */
    data class ReceivedImage(
        val uri: Uri,
        val name: String,
        val eventId: Long,
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

    /**
     * Is the user's Submit press still "live"? Verification only happens while
     * this is true, which is what makes "one Submit = one PIN attempt".
     *
     * WHY THIS EXISTS: the Mac reconnects roughly every 1.5s while showing a
     * PIN. Without this gate, any 6 digits sitting in the box would be checked
     * on every one of those reconnects — five wrong guesses in about seven
     * seconds, and a lockout, without the user ever pressing anything.
     */
    @Volatile
    private var pinSubmitted = false

    /**
     * Android pairing requests the user explicitly cancelled, keyed
     * `name|requestId`. The client retries every 1.5 s (so the user has time to
     * type the PIN), and without this set Cancel would reopen the prompt on the
     * very next retry. A NEW pairing session from the same phone has a new
     * request id, so it is allowed to ask again.
     */
    private val declinedRequests = mutableSetOf<String>()

    /** The phone pairing request currently shown, if any. */
    private var pendingAndroidRequest: Pair<String, String>? = null

    /**
     * True while this phone is acting as the CLIENT for another phone. In that
     * mode the queue belongs to the phone peer, not to a Mac, so the Mac must
     * not pull it out from under the gesture flow.
     */
    @Volatile
    private var peerClientActive = false

    /**
     * True when the user picked a Mac as the send target. The queue then belongs
     * to that Mac, so a phone peer that is also paired must not pull it.
     */
    @Volatile
    private var macTargetActive = false

    /**
     * True when the last device paired/selected here was a phone. The queue
     * then belongs to that phone, so a Mac that happens to be connected must
     * not pull it out from under the gesture flow.
     */
    @Volatile
    private var peerTargetActive = false

    /** Called by the UI when a Mac (not a phone) is selected as the target. */
    fun setMacTargetActive(active: Boolean) {
        macTargetActive = active
        if (active) {
            peerTargetActive = false
            _state.value = _state.value.copy(phoneTargetActive = false)
        }
    }

    /** Called by the UI every time the PIN field changes. */
    fun setPin(pin: String) {
        // Only ever accept up to 6 digits — anything else is dropped so a stray
        // letter can't make the phone reject a correct PIN for the wrong reason.
        _typedPin.value = pin.filter { it.isDigit() }.take(6)
        // Editing the PIN cancels a pending Submit: each new press is a fresh
        // attempt, and a stale press must not verify whatever is typed next.
        pinSubmitted = false
    }

    /** Called when the user presses Submit. Arms exactly one verification. */
    fun submitPin() {
        pinSubmitted = true
    }

    fun resetPairing() {
        _typedPin.value = ""
        pinSubmitted = false
        val prefs = context.applicationContext.getSharedPreferences("aavija_pairing", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        _state.value = _state.value.copy(
            isPaired = false,
            pairedMacName = null,
            pairedPhoneName = null,
            lastPairing = null,
            selectedMac = null,
            pairingRequestActive = false,
        )
    }

    /**
     * Unpairs a specific device (Mac, Windows PC, or Phone) or clears all if name is null.
     */
    fun unpairDevice(name: String? = null) {
        val currentMac = _state.value.pairedMacName
        val currentPhone = _state.value.pairedPhoneName

        val isMacMatch = name == null || currentMac == null ||
            name.equals(currentMac, ignoreCase = true) ||
            name.contains(currentMac, ignoreCase = true) ||
            currentMac.contains(name, ignoreCase = true) ||
            name.contains("mac", ignoreCase = true) ||
            name.contains("windows", ignoreCase = true) ||
            name.contains("pc", ignoreCase = true)

        if (isMacMatch) {
            val prefs = context.applicationContext.getSharedPreferences("aavija_pairing", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            _state.value = _state.value.copy(
                isPaired = false,
                pairedMacName = null,
                selectedMac = null,
                lastPairing = null,
                pairingRequestActive = false,
            )
        }

        val isPhoneMatch = name == null || currentPhone == null ||
            name.equals(currentPhone, ignoreCase = true) ||
            name.contains(currentPhone, ignoreCase = true) ||
            currentPhone.contains(name, ignoreCase = true) ||
            name.contains("phone", ignoreCase = true) ||
            name.contains("android", ignoreCase = true)

        if (isPhoneMatch) {
            _state.value = _state.value.copy(
                pairedPhoneName = null,
                phoneTargetActive = false,
            )
        }
    }

    /**
     * Called when the user taps a Mac in the list (or clears the selection).
     *
     * Picking a device always starts a clean conversation: an old PIN is
     * dropped (verifying last time's digits against a NEW PIN would be
     * baffling), and whatever we complained about belonged to the Mac that is
     * no longer selected.
     */
    fun selectMac(name: String?) {
        _typedPin.value = ""
        pinSubmitted = false
        pendingAndroidRequest = null
        // Tapping a device starts a pairing conversation. A device we are
        // ALREADY paired with has no PIN prompt; a NEW one does — even if this
        // phone is currently paired with a different device (a Mac or a phone).
        val alreadyPairedName = _state.value.pairedMacName
        _state.value = _state.value.copy(
            selectedMac = name,
            lastRejection = null,
            pairingRequestActive = name != null && name != alreadyPairedName,
            // Picking a Mac changes the send target, but it must NOT forget the
            // phone that paired with us as a listener: that phone is still
            // trusted and its row must keep saying so.
            phoneTargetActive = if (name != null) false else _state.value.phoneTargetActive,
        )
    }

    /**
     * Close the PIN prompt because the user pressed Cancel.
     *
     * For an Android request we also remember the exact request id and answer
     * its next retry with "declined", so the prompt cannot immediately pop
     * back. The user can start a fresh session from the other phone whenever
     * they want — that carries a new request id.
     */
    fun dismissPairingRequest() {
        val pending = pendingAndroidRequest
        if (pending != null) {
            synchronized(declinedRequests) {
                if (declinedRequests.size > 64) declinedRequests.clear()
                declinedRequests.add(requestKey(pending.first, pending.second))
            }
        }
        pendingAndroidRequest = null
        _typedPin.value = ""
        pinSubmitted = false
        _state.value = _state.value.copy(
            selectedMac = null,
            pairingRequestActive = false,
            isWaitingForPin = false,
            lastRejection = null,
        )
    }

    /** Let the client connector reserve the outgoing queue for a phone peer. */
    fun setPeerClientActive(active: Boolean) {
        peerClientActive = active
    }

    /**
     * Make the phone that paired with US the queue owner again.
     *
     * That phone dials us, so there is no outbound connection to "activate".
     * We only have to say the queue is its lane again; its next heartbeat sees
     * the queued file and pulls it. Returns false when [phoneName] is not the
     * listener-side pairing (the UI then falls back to normal client pairing).
     */
    fun resumePhoneTarget(phoneName: String): Boolean {
        if (_state.value.pairedPhoneName != phoneName) return false
        peerTargetActive = true
        macTargetActive = false
        _state.value = _state.value.copy(phoneTargetActive = true)
        return true
    }

    /** The first file waiting to go to the paired peer (or Mac). */
    fun peekOutgoing(): OutgoingFile? = outgoingQueue.peek()

    /** Drop a file only after the peer confirmed it arrived. */
    fun removeOutgoing(file: OutgoingFile) {
        outgoingQueue.remove(file)
    }

    /**
     * Publish a phone-to-phone transfer on the same status row the Mac
     * transfers use, so the screen has one source of truth.
     */
    fun reportPeerTransfer(
        status: String?,
        failed: Boolean = false,
        isSending: Boolean = false,
        isReceiving: Boolean = false,
    ) {
        _state.value = _state.value.copy(
            transferStatus = status,
            transferFailed = failed,
            isSending = isSending,
            isReceiving = isReceiving,
        )
    }

    /** A phone peer saved an image; let the existing viewer open it at once. */
    fun onPeerFileSaved(uri: Uri?, filename: String) {
        if (uri != null && ReceivedFiles.isImageFile(filename)) {
            _state.value = _state.value.copy(
                receivedImage = ReceivedImage(
                    uri = uri,
                    name = filename,
                    eventId = ++receivedImageEventId,
                ),
            )
        }
    }

    /** True while a verification may actually run. Read by the protocol. */
    private fun isPinSubmitted(): Boolean = pinSubmitted

    /** Which Mac may pair right now. Read by the protocol on each connection. */
    private fun selectedMacName(): String = _state.value.selectedMac.orEmpty()


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
    private val outgoingQueue = OutgoingQueue()
    private val gestureReporter = GestureReporter(clock = { System.currentTimeMillis() })

    /** Increments once per saved image, so the viewer opens only for new ones. */
    @Volatile
    private var receivedImageEventId = 0L
    @Volatile
    private var gestureModeOn = false
    @Volatile
    private var directReleaseActiveUntil = 0L

    /** Called by MainActivity when Gesture mode is toggled. */
    fun setGestureMode(enabled: Boolean) {
        gestureModeOn = enabled
        if (!enabled) {
            gestureReporter.reset()
            _state.value = _state.value.copy(gestureMode = false, lastGesture = null)
        } else {
            _state.value = _state.value.copy(gestureMode = true)
        }
    }

    /** Called when the camera detects a GRAB/RELEASE. */
    fun onGesture(type: HandGestureMachine.EventType) {
        gestureReporter.record(type)
        val text = when (type) {
            HandGestureMachine.EventType.GRAB -> "GRAB ✊"
            HandGestureMachine.EventType.RELEASE -> "RELEASE 🖐"
        }
        _state.value = _state.value.copy(lastGesture = text)
    }

    fun queueOutgoingFile(uri: Uri, name: String, size: Long, sha256Hex: String) {
        if (size > MAX_FILE_BYTES) {
            _state.value = _state.value.copy(transferFailed = true, transferStatus = "File too large (max 200 MB).")
            return
        }
        // The user deliberately picked a file. It replaces a staged screenshot:
        // one queue, and the newest choice wins.
        outgoingQueue.discardScreenshots()
        outgoingQueue.enqueue(OutgoingFile(uri, name, size, sha256Hex))
        _state.value = _state.value.copy(
            transferFailed = false,
            isStagedFileGrabbed = false,
            stagedFileName = name,
            transferStatus = if (gestureModeOn) {
                "Ready to beam $name — Aim & RELEASE 🖐 or GRAB ✊"
            } else {
                "Ready to beam $name — tap Direct Release 🖐 or choose device"
            },
        )
    }

    /** User gripped the staged file in hand (GRAB ✊ gesture) without screenshot capture. */
    fun markFileGrabbed(grabbed: Boolean) {
        _state.value = _state.value.copy(isStagedFileGrabbed = grabbed)
    }

    /** Clear any staged outgoing file. */
    fun discardOutgoingFile() {
        if (_state.value.isSending) return
        outgoingQueue.clear()
        _state.value = _state.value.copy(
            transferStatus = null,
            transferFailed = false,
            isStagedFileGrabbed = false,
            stagedFileName = null,
        )
    }

    /** Trigger immediate mid-air Direct Release (🖐) transmission. */
    fun triggerDirectRelease() {
        val file = outgoingQueue.peek() ?: return
        directReleaseActiveUntil = System.currentTimeMillis() + 10_000L
        gestureReporter.record(HandGestureMachine.EventType.GRAB)
        gestureReporter.record(HandGestureMachine.EventType.RELEASE)
        _state.value = _state.value.copy(
            lastGesture = "RELEASE 🖐",
            transferFailed = false,
            isStagedFileGrabbed = false,
            transferStatus = "Direct Release 🖐: Beaming ${file.name}…",
        )
        AavijaSounds.transferStart()
    }

    /**
     * Stage a screenshot captured on GRAB. Returns false when it could not be
     * queued (too large, or a transfer is already using the queue).
     */
    fun queueScreenshot(file: File, name: String, size: Long, sha256Hex: String): Boolean {
        if (size > MAX_FILE_BYTES) {
            _state.value = _state.value.copy(
                transferFailed = true,
                transferStatus = "Screenshot is larger than the 200 MB limit.",
            )
            return false
        }
        if (_state.value.isSending) return false

        outgoingQueue.replaceScreenshot(
            OutgoingFile(
                uri = Uri.fromFile(file),
                name = name,
                size = size,
                sha256Hex = sha256Hex,
                isScreenshot = true,
                localFile = file,
            ),
        )
        _state.value = _state.value.copy(
            transferFailed = false,
            transferStatus = if (gestureModeOn) {
                "Screenshot ready — RELEASE on the other device within 10 s"
            } else {
                "Screenshot ready — waiting for the other device"
            },
        )
        return true
    }

    /** True when GRAB may replace the queue with a screenshot. */
    fun canHoldScreenshot(): Boolean = outgoingQueue.peek()?.let { it.isScreenshot } ?: true

    /** Forget any staged screenshot (gesture mode off). */
    fun discardScreenshots() {
        // Never delete the file while the sender is reading it.
        if (_state.value.isSending) return
        outgoingQueue.discardScreenshots()
    }

    /** Let the runtime publish a plain status (e.g. "Capturing screenshot…"). */
    fun reportStatus(message: String, failed: Boolean = false) {
        _state.value = _state.value.copy(
            transferStatus = message,
            transferFailed = failed,
        )
    }

    /** Let the UI report a read/pick failure (e.g. hashing couldn't open the stream). */
    fun reportOutgoingProblem(msg: String) {
        _state.value = _state.value.copy(transferFailed = true, transferStatus = msg)
    }

    fun outgoingCount(): Int = outgoingQueue.count()

    /** Open the port and announce it. Safe to call once from onCreate. */
    fun start() {
        if (_state.value.isAdvertising) return

        // Load the persisted attempt counter / lockout BEFORE anything else.
        // A force-quit must not hide a lockout — otherwise quitting the app
        // becomes a way to reset the 5-attempt limit.
        val pairing = PinPairing(pairingStore) { System.currentTimeMillis() }
        // Pairing SURVIVES restarts — the tokens are on disk — so the screen
        // must come back saying "paired" too. Otherwise closing or minimizing
        // the app looked like it had forgotten the Mac: `isPaired` used to be
        // set only by a live reply, never read back from the store.
        val alreadyPaired = pairing.hasPairedToken()
        _state.value = _state.value.copy(
            attemptsRemaining = pairing.attemptsRemaining(),
            lockedUntilMs = pairingStore.lockedUntilMs(),
            isPaired = alreadyPaired,
            pairedMacName = if (alreadyPaired) pairingStore.pairedMacName() else null,
            // A phone that paired with US lives only in AdState, so without
            // this it vanished every time the app was paused and restarted.
            pairedPhoneName = displayPrefs().getString(KEY_PAIRED_PHONE, null),
            phoneTargetActive = peerTargetActive,
            lastPairing = if (alreadyPaired) "Paired successfully" else null,
        )

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
                // A random per-install id. Two identical phone models share a
                // Bonjour name, so this is what lets PeerDiscovery skip OUR OWN
                // advertisement without ever asking another phone.
                setAttribute("id", DeviceIdentity.id(context))
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

    /** Small private store for display-only labels that outlive a pause. */
    private fun displayPrefs() = context.applicationContext
        .getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE)

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
        // One stalled client must not block the single accept thread forever.
        runCatching { conn.soTimeout = SOCKET_READ_TIMEOUT_MS }

        // Buffered ONCE, and the same buffer carries both the text lines and the
        // raw file bytes. A second reader (say, a BufferedReader) would happily
        // swallow the first bytes of the file while hunting for its newline —
        // one buffer, read strictly in order, is what keeps the payload intact.
        val input = BufferedInputStream(conn.getInputStream())
        val output = BufferedOutputStream(conn.getOutputStream())

        // A FRESH salt every connection = replay protection: a proof captured
        // on one attempt is useless against the next challenge.
        val salt = PairingCrypto.randomToken()
        val protocol = PairingProtocol(
            salt = salt,
            phoneFp = phoneFingerprint,
            store = pairingStore,
            selectedMac = ::selectedMacName,
            pinSubmitted = ::isPinSubmitted,
            typedPin = { _typedPin.value },
        )
        protocol.setOutgoingCountProvider { fromPhone ->
            // One queue, one owner. The same rule gates the heartbeat count and
            // the actual `receive` handler in receiveFile().
            if (PeerProtocol.queueVisibleTo(
                    fromPhone = fromPhone,
                    peerClientActive = peerClientActive,
                    peerTargetActive = peerTargetActive,
                    macTargetActive = macTargetActive,
                )
            ) {
                outgoingQueue.count()
            } else {
                0
            }
        }
        // Phase 7: while Gesture mode is ON, the Mac must NOT pull a queued file
        // just because it exists, unless Direct Release was just triggered.
        protocol.setGestureGateProvider {
            gestureModeOn && System.currentTimeMillis() > directReleaseActiveUntil
        }
        protocol.setGestureOfferProvider { gestureReporter.offer() }

        // 1) Send the challenge so the Mac can build its proof.
        FileTransfer.writeLine(output, protocol.challengeLine())

        // 2) Read the Mac's first message. Three shapes are normal here:
        //      "poll" — the Mac asking "did the user tap my name?" (Phase 5b)
        //      "pair" — a first-time pairing attempt
        //      "auth" — an already-paired Mac proving it holds a token, which is
        //               also how every FILE TRANSFER begins (Phase 4a)
        val line = FileTransfer.readLine(input)
        if (line.isNullOrBlank()) {
            Log.w(TAG, "Connection sent no pairing message")
            return
        }
        Log.i(TAG, "Pairing message: $line")

        // Android-to-Android: the CLIENT phone cannot show a PIN prompt on this
        // phone, so an incoming phone pairing request opens one here. The user
        // types the PIN that the client phone is displaying, exactly as they
        // would type a Mac's PIN.
        if (PeerProtocol.isAndroidCancel(line)) {
            handleAndroidCancel(line)
            runCatching {
                FileTransfer.writeLine(
                    output,
                    JSONObject().put("type", "cancelled").toString(),
                )
            }
            return
        }
        if (PeerProtocol.isAndroidPair(line)) {
            val key = requestKey(
                PeerProtocol.senderName(line),
                PeerProtocol.requestId(line),
            )
            val declined = synchronized(declinedRequests) {
                key in declinedRequests
            }
            if (declined) {
                FileTransfer.writeLine(
                    output,
                    JSONObject().put("type", "rejected").put("reason", "declined").toString(),
                )
                pinSubmitted = false
                return
            }
            maybeShowAndroidPairing(line)
        }

        // 3) Verify and reply.
        val reply = protocol.handle(line)
        FileTransfer.writeLine(output, reply)
        Log.i(TAG, "Pairing reply: $reply")

        // 4) Publish the outcome so the phone's screen can show it.
        publishPairingOutcome(reply)

        // The device that just proved itself becomes the queue owner. This is
        // the single-lane rule: a Mac must not pull a file the user meant for a
        // phone, and a phone must not pull one meant for a Mac.
        val androidSender = if (PeerProtocol.isAndroidPair(line)) {
            PeerProtocol.senderName(line)
        } else {
            null
        }
        val fromPhone = androidSender != null || PeerProtocol.isAndroidAuth(line)
        if (isPairedReply(reply)) {
            if (fromPhone) {
                peerTargetActive = true
                macTargetActive = false
                if (androidSender != null) {
                    displayPrefs().edit().putString(KEY_PAIRED_PHONE, androidSender).apply()
                    _state.value = _state.value.copy(
                        pairedPhoneName = androidSender,
                        phoneTargetActive = true,
                    )
                } else {
                    // A returning phone proved its token (push/pull): it is the
                    // device currently using the queue.
                    _state.value = _state.value.copy(phoneTargetActive = true)
                }
            } else {
                setMacTargetActive(true)
                // Keep any listener-side phone pairing remembered: trust and
                // "which queue" are separate questions.
            }
        }

        // 5) A Mac that JUST authenticated is trusted — it may follow with a
        //    file. An unknown token never gets this far, so file bytes are
        //    refused for unpaired devices by construction.
        if (isAuthenticated(reply)) {
            receiveFile(input, output)
        }
    }

    private fun isAuthenticated(reply: String): Boolean = try {
        JSONObject(reply).optString("type") == "authenticated"
    } catch (_: Exception) {
        false
    }

    /** Did this reply prove a device's identity (first pairing OR token auth)? */
    private fun isPairedReply(reply: String): Boolean = try {
        val type = JSONObject(reply).optString("type")
        type == "paired" || type == "authenticated"
    } catch (_: Exception) {
        false
    }

    /**
     * Open this phone's PIN prompt for an incoming Android pairing request.
     *
     * We do NOT override a device the user explicitly picked: [PairingProtocol]
     * will answer "not the selected device" in that case, which is the same
     * protection a second Mac already gets.
     */
    private fun maybeShowAndroidPairing(line: String) {
        val sender = PeerProtocol.senderName(line)
        if (sender.isEmpty()) return

        val current = _state.value
        // Never yank a live prompt away from the device the user is typing a
        // PIN for. That would reset the field mid-attempt and turn a correct
        // PIN into a baffling failure. Once that prompt closes (paired,
        // cancelled, or rejected) a phone may take the stage.
        if (current.pairingRequestActive && current.selectedMac != sender) return

        val requestId = PeerProtocol.requestId(line)
        val changed = current.selectedMac != sender
        if (changed) {
            // A different device is asking: drop last session's PIN cleanly.
            _typedPin.value = ""
            pinSubmitted = false
        }
        pendingAndroidRequest = sender to requestId
        _state.value = current.copy(
            selectedMac = sender,
            pairingRequestActive = true,
            lastRejection = if (changed) null else current.lastRejection,
        )
    }

    /** The client phone cancelled its pairing session; close our prompt too. */
    private fun handleAndroidCancel(line: String) {
        val pending = pendingAndroidRequest ?: return
        val requestId = PeerProtocol.requestId(line)
        if (requestId.isNotEmpty() && pending.second.isNotEmpty() &&
            requestId != pending.second
        ) {
            return
        }
        pendingAndroidRequest = null
        _typedPin.value = ""
        pinSubmitted = false
        _state.value = _state.value.copy(
            selectedMac = null,
            pairingRequestActive = false,
            isWaitingForPin = false,
        )
    }

    private fun requestKey(name: String, requestId: String): String =
        "$name\u0000$requestId"

    /** A file header is on the wire; check it, then stream the body. */
    private fun receiveFile(input: BufferedInputStream, output: BufferedOutputStream) {
        val headerLine = FileTransfer.readLine(input)
        if (headerLine.isNullOrBlank()) {
            Log.w(TAG, "Authenticated Mac sent no file header")
            return
        }
        Log.i(TAG, "File header: $headerLine")
        // Try to parse; might be a request to receive (phone->Mac) rather than a file header?
        val obj = try { JSONObject(headerLine) } catch (_: Exception) { null }
        if (obj != null && obj.optString("type") == "receive") {
            val fromPhone = obj.optString("platform") == PeerProtocol.PLATFORM_ANDROID
            if (!PeerProtocol.queueVisibleTo(
                    fromPhone = fromPhone,
                    peerClientActive = peerClientActive,
                    peerTargetActive = peerTargetActive,
                    macTargetActive = macTargetActive,
                )
            ) {
                // The queue belongs to a different target; tell the caller
                // there is nothing for it instead of sending someone else's file.
                runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "idle").toString()) }
                return
            }
            // The queue owner wants to fetch a queued file from this phone → send it
            sendQueuedFile(input, output)
            return
        }

        when (val parsed = FileTransfer.parseHeader(headerLine)) {
            is FileTransfer.HeaderResult.Bad -> {
                Log.w(TAG, "Refused file: ${parsed.reason}")
                runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "failed").put("reason", parsed.reason).toString()) }
                _state.value = _state.value.copy(
                    isReceiving = false,
                    transferFailed = true,
                    transferStatus = "Refused a file: ${parsed.reason}",
                )
            }
            is FileTransfer.HeaderResult.Ok -> storeFile(input, output, parsed.header)
        }
    }

    /** Stream the body into Downloads/GestureShare, keeping it only on a hash match. */
    private fun storeFile(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        header: FileTransfer.Header,
    ) {
        _state.value = _state.value.copy(
            isReceiving = true,
            transferFailed = false,
            transferStatus = "આવતું છે... Receiving ${header.filename}",
        )
        AavijaSounds.transferStart()

        val runtime = AavijaRuntime.get(context)
        if (!runtime.uiVisible) {
            wakeAndLaunchIncomingActivity(context, header.filename)
        }

        val sink = try {
            ReceivedFiles.open(context, header.filename)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the destination file", e)
            runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "failed").put("reason", "cannot write to Downloads").toString()) }
            _state.value = _state.value.copy(
                isReceiving = false,
                transferFailed = true,
                transferStatus = "Could not save ${header.filename}",
            )
            return
        }

        try {
            val got = FileTransfer.receive(input, sink.output, header.size)
            if (got == header.sha256Hex) {
                val savedUri = sink.commit()
                Log.i(TAG, "File received OK: ${header.filename} (${header.size} bytes)")
                runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "received").toString()) }
                val saved = _state.value.copy(
                    isReceiving = false,
                    transferFailed = false,
                    transferStatus = "Saved ${header.filename} to ${ReceivedFiles.DISPLAY_FOLDER}",
                )
                // Screenshot sharing: a received image opens by itself in the new viewer.
                // The event id is what tells the UI this is a NEW photo rather
                // than the same one coming back on a recomposition.
                AavijaSounds.transferComplete()
                if (savedUri != null && ReceivedFiles.isImageFile(header.filename)) {
                    val img = ReceivedImage(
                        uri = savedUri,
                        name = header.filename,
                        eventId = ++receivedImageEventId,
                    )
                    _state.value = saved.copy(receivedImage = img)
                    // If the app is in the background, surface the photo via a
                    // notification so the user can view it without opening Aavija.
                    val runtime = AavijaRuntime.get(context)
                    if (!runtime.uiVisible) {
                        runtime.pendingReceivedImage.value = img
                        postReceivedImageNotification(context, savedUri, header.filename)
                    }
                } else {
                    _state.value = saved
                }
            } else {
                // Never leave a tampered / truncated file where it can be opened.
                sink.abort()
                Log.w(TAG, "Hash mismatch for ${header.filename} — deleted")
                runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "failed").put("reason", "hash mismatch").toString()) }
                _state.value = _state.value.copy(
                    isReceiving = false,
                    transferFailed = true,
                    transferStatus = "Deleted ${header.filename}: hash mismatch",
                )
            }
        } catch (e: Exception) {
            sink.abort()
            Log.e(TAG, "File receive failed", e)
            runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "failed").put("reason", e.message ?: "transfer failed").toString()) }
            _state.value = _state.value.copy(
                isReceiving = false,
                transferFailed = true,
                transferStatus = "Transfer failed: ${e.message ?: "unknown error"}",
            )
        }
    }

    private fun sendQueuedFile(input: BufferedInputStream, output: BufferedOutputStream) {
        // PEEK, don't dequeue: if the send fails we must not silently lose the
        // file — it stays queued and the Mac will ask again on its next ping.
        val file = outgoingQueue.peek()
        if (file == null) {
            FileTransfer.writeLine(output, JSONObject().put("type", "idle").toString())
            return
        }
        // Phase 8: drives the orb overlay for the duration of the send.
        _state.value = _state.value.copy(
            isSending = true,
            transferFailed = false,
            transferStatus = "Sending ${file.name}…",
        )
        AavijaSounds.transferStart()
        // Send header
        val header = JSONObject()
            .put("type", "file")
            .put("filename", file.name)
            .put("size", file.size)
            .put("sha256", file.sha256Hex)
            .toString()
        // Send header + body
        try {
            FileTransfer.writeLine(output, header)
            val stream = context.contentResolver.openInputStream(file.uri)
                ?: throw IllegalStateException("file is no longer readable")
            stream.use { f ->
                FileTransfer.sendBytes(f, output, file.size)
            }
            // Delivered: the Mac verified the hash on its side. Now it's safe to
            // forget it.
            outgoingQueue.remove(file)
            directReleaseActiveUntil = 0L
            // The Mac closes as soon as it has the last byte, so this courtesy
            // line can race a closed socket. Never let that turn a good transfer
            // into a "failed" row on the phone.
            runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "received").toString()) }
            AavijaSounds.transferComplete()
            _state.value = _state.value.copy(
                isSending = false,
                transferFailed = false,
                isStagedFileGrabbed = false,
                stagedFileName = null,
                transferStatus = "Sent ${file.name} to Mac ✓",
            )
        } catch (e: Exception) {
            runCatching { FileTransfer.writeLine(output, JSONObject().put("type", "failed").put("reason", e.message ?: "send failed").toString()) }
            _state.value = _state.value.copy(isSending = false, transferFailed = true, transferStatus = "Failed to send ${file.name}: ${e.message}")
        }
    }

    /**
     * Turn a protocol reply line into a status the UI can display.
     *
     * A "no pin entered" rejection is NOT a real failure — it just means the
     * Mac is pairing but nobody has pressed Submit on this phone yet. We flag
     * that as "waiting" so the screen shows a calm hint, not a red error.
     */
    private fun publishPairingOutcome(reply: String) {
        val obj = try { JSONObject(reply) } catch (_: Exception) { return }

        // A poll reply is the Mac asking to be told to show a PIN. It is not a
        // verdict, so it must NOT touch the attempt counter or the lockout —
        // it verifies nothing. It must also not overwrite `lastPairing`: the
        // user may still be reading "Wrong PIN" from a moment ago.
        if (obj.optString("type") == "poll") {
            if (obj.optString("action") == "pair") {
                _state.value = _state.value.copy(isWaitingForPin = true)
            }
            return
        }

        val type = obj.optString("type")
        val reason = obj.optString("reason")

        val waiting = (type == "rejected" && reason == "no pin entered")
        val wrongPin = (type == "rejected" && reason == "wrong pin")

        val pairing = PinPairing(pairingStore) { System.currentTimeMillis() }
        val alreadyPaired = pairing.hasPairedToken()

        val (status, justPaired) = when {
            type == "paired" -> "Paired successfully" to true
            type == "authenticated" -> "Reconnected (token accepted)" to true
            type == "locked" -> "Too many wrong PINs — locked out" to false
            waiting -> "Waiting for you to press Submit" to false
            reason == "not the selected device" ->
                "A different device tried to pair — tap it in the list first" to false
            type == "rejected" -> "Rejected: $reason" to false
            else -> return
        }

        // Every real verdict ends the armed Submit: the next attempt must only
        // happen when the user presses Submit again. A "waiting" reply is the
        // one exception: it means this connection checked BEFORE the user's
        // Submit landed, so clearing it here would silently eat that press and
        // leave the user staring at a phone that never verifies.
        if (!waiting) pinSubmitted = false
        if (justPaired) pendingAndroidRequest = null

        // A success clears any earlier complaint; a failure replaces it. This
        // is the line the popup keeps showing, so it has to be exact.
        val rejection = when {
            justPaired -> null
            type == "locked" -> "Locked out"
            wrongPin -> "Wrong PIN"
            waiting -> _state.value.lastRejection // keep the old complaint visible
            else -> _state.value.lastRejection
        }

        // A wrong PIN from a second phone must NOT erase an existing pairing
        // with a Mac. `isPaired` means "we have SOME trusted token", not "this
        // one attempt just succeeded".
        val nowPaired = justPaired || alreadyPaired
        val promptActive = when {
            justPaired || type == "locked" -> false
            waiting || wrongPin -> true
            else -> _state.value.pairingRequestActive
        }

        _state.value = _state.value.copy(
            lastPairing = status,
            isPaired = nowPaired,
            // On success, read back the name the protocol just saved.
            pairedMacName = if (nowPaired) {
                pairingStore.pairedMacName() ?: _state.value.pairedMacName
            } else {
                _state.value.pairedMacName
            },
            isWaitingForPin = waiting,
            pairingRequestActive = promptActive,
            attemptsRemaining = pairing.attemptsRemaining(),
            lockedUntilMs = pairingStore.lockedUntilMs(),
            wrongPinCount = _state.value.wrongPinCount + if (wrongPin) 1 else 0,
            lastRejection = rejection,
        )
    }
}

/** Post a notification so a received image can be viewed without opening Aavija. */
private fun postReceivedImageNotification(context: Context, uri: android.net.Uri, name: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "aavija_images"
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "Received images", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("show_image_uri", uri.toString())
        putExtra("show_image_name", name)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pending = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val bitmap = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setContentTitle("Aavija")
        .setContentText("New image received: $name")
        .setContentIntent(pending)
        .setAutoCancel(true)
    if (bitmap != null) {
        builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
    }
    manager.notify(4202, builder.build())
}

/** Wakes the device and launches MainActivity with the "આવતું છે" animation when receiving files in background. */
fun wakeAndLaunchIncomingActivity(context: Context, filename: String) {
    try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "aavija_incoming_transfer"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Incoming Transfers (આવતું છે)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Wakes screen and opens Aavija when files stream via mid-air gestures"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("incoming_transfer", true)
            putExtra("incoming_filename", filename)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            4203,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_gesture)
            .setContentTitle("આવતું છે ⚡ Receiving $filename")
            .setContentText("Incoming file via Aavija mid-air gesture share…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .setAutoCancel(true)
            .build()

        manager.notify(4203, notification)
        context.startActivity(launchIntent)
    } catch (e: Exception) {
        Log.e("Aavija", "Could not wake activity for incoming transfer: ${e.message}")
    }
}
