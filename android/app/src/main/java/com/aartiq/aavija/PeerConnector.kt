package com.aartiq.aavija

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The CLIENT half of phone-to-phone sharing.
 *
 * The two phones in an Aavija session are not equal: both listen, but only one
 * can dial. This class is that one. It speaks the exact protocol the Mac
 * already speaks to a phone, which is why no second server implementation was
 * needed:
 *
 *  - pair: read challenge → send PIN proof → keep the minted token
 *  - heartbeat: read challenge → `ping` with the token → read `pong`
 *  - send:   `auth` → send `file` header + bytes
 *  - pull:   `auth` → send `receive` → save the header + bytes
 *
 * Gestures are matched here with [PeerGestureMatcher], mirroring the Mac's
 * driver model: the connecting phone turns a matching GRAB/RELEASE into a
 * transfer. The listener only reports its own gesture on the authenticated
 * pong.
 *
 * ### Security
 * PIN pairing is unchanged: the proof is bound to the peer certificate's
 * fingerprint, the peer mints a 256-bit token, and every file byte is preceded
 * by a token `auth`. This client trusts the self-signed certificate only to
 * OPEN the tunnel (trust-on-first-use), never to send a byte: the server still
 * accepts the token check first. Five wrong PINs still lock the server out.
 */
class PeerConnector(
    private val context: Context,
    private val advertiser: BonjourAdvertiser,
    private val discovery: PeerDiscovery,
) {

    companion object {
        private const val TAG = "AavijaPeerConnector"

        private const val PAIR_RETRY_MS = 1_500L
        private const val PAIR_TIMEOUT_MS = 120_000L
        private const val HEARTBEAT_FAST_MS = 1_500L
        private const val HEARTBEAT_SLOW_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 12_000
    }

    /**
     * What the phone UI needs to know about the outbound side. Transfer text
     * and the orb flags live in [BonjourAdvertiser.AdState], shared with the
     * listener side so the screen has one status area.
     */
    data class State(
        val isPairing: Boolean = false,
        val pin: String? = null,
        val pairingPeerName: String? = null,
        val pairingStatus: String? = null,
        val pairingFailed: Boolean = false,
        val isPaired: Boolean = false,
        val activePeerId: String? = null,
        val pairedPeerName: String? = null,
        val pairedPeerIds: Set<String> = emptySet(),
        val isReachable: Boolean = false,
        /** Is the paired phone the device the next file should go to? */
        val targetActive: Boolean = false,
    )

    private data class Target(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    private data class PairAttempt(
        val verdict: PeerProtocol.Verdict,
        val fingerprint: String,
    )

    private class Connection(
        val socket: SSLSocket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
        val fingerprint: String,
    ) : Closeable {
        override fun close() {
            runCatching { output.flush() }
            runCatching { socket.close() }
        }
    }

    private val store = PrefsPeerPairingStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val matcher = PeerGestureMatcher()

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var stopped = false
    @Volatile private var gestureMode = false
    @Volatile private var remoteGestureGate = false

    /**
     * Is the phone peer the CURRENT send target?
     *
     * A phone can be paired with both a Mac and another phone. The queue is a
     * single lane, so the last device the user picked owns it. Tapping a Mac
     * calls [pauseTarget]; tapping/pairing a phone sets this true again. Without
     * this flag a file meant for the Mac could be pushed to the phone on the
     * next heartbeat.
     */
    @Volatile private var targetActive = true

    private var pairingJob: Job? = null
    private var heartbeatJob: Job? = null

    private var pairTarget: Target? = null
    private var pairRequestId: String? = null

    private val transferLock = Any()
    private var pushing = false
    private var pulling = false

    /** A RELEASE matched while a screenshot was still being captured. */
    @Volatile
    private var pendingSendAfterCapture = false

    /** Read by the matcher: is a screenshot being captured right now? */
    @Volatile
    private var screenshotPending: () -> Boolean = { false }

    fun setScreenshotPendingProvider(provider: () -> Boolean) {
        screenshotPending = provider
    }

    /** Called by the runtime once a GRAB's screenshot is safely queued. */
    fun onScreenshotStaged() {
        if (!pendingSendAfterCapture) return
        pendingSendAfterCapture = false
        if (targetActive && !stopped) maybePush()
    }

    /** The screenshot could not be captured; fail the pending gesture clearly. */
    fun onScreenshotUnavailable(message: String) {
        if (!pendingSendAfterCapture) return
        pendingSendAfterCapture = false
        advertiser.reportPeerTransfer(message, failed = true)
    }

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    private fun loadInitialState(): State {
        val peers = store.peers()
        val active = store.activePeerId
            ?.let { id -> peers.firstOrNull { it.id == id } }
            ?: peers.firstOrNull()
        if (active != null) store.activePeerId = active.id
        return State(
            isPaired = active != null,
            activePeerId = active?.id,
            pairedPeerName = active?.name,
            pairedPeerIds = peers.map { it.id }.toSet(),
            targetActive = active != null,
        )
    }

    // MARK: - lifecycle

    /** Called from the activity's onStart. */
    fun start() {
        stopped = false
        refreshPairedIds()
        if (store.activePeerId != null) {
            // Re-assert queue ownership after a stop/start cycle: the peer is
            // still the last target the user picked.
            advertiser.setPeerClientActive(targetActive)
            ensureHeartbeat()
        }
    }

    /** Called from the activity's onStop. Pairing and heartbeat both stop. */
    fun stop() {
        // Leaving the app mid-pairing must not leave a dead dialog on screen
        // when we come back. Cancel cleanly and tell the other phone too.
        if (_state.value.isPairing) cancelPairing()
        stopped = true
        pendingSendAfterCapture = false
        pairingJob?.cancel()
        pairingJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** Release the coroutine scope (onDestroy). */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    // MARK: - pairing

    /** Tap a phone in the list: connect immediately if trusted, else pair. */
    fun pairWith(peer: PeerDiscovery.PhonePeer) {
        if (store.find(peer.id) != null) {
            activate(peer)
            return
        }

        cancelPairingInternal()
        // The user is deliberately picking a new phone; keep the single queue
        // away from the previous target until this pairing succeeds.
        pauseTarget()
        val target = Target(peer.id, peer.name, peer.host, peer.port)
        val pin = generatePin()
        val requestId = newRequestId()

        pairTarget = target
        pairRequestId = requestId
        _state.value = _state.value.copy(
            isPairing = true,
            pin = pin,
            pairingPeerName = peer.name,
            pairingStatus = "Type this PIN on ${peer.name} to pair.",
            pairingFailed = false,
        )

        pairingJob = scope.launch {
            pairingLoop(target, pin, requestId)
        }
    }

    /** Switch to a phone we already trust, and start its heartbeat. */
    fun activate(peer: PeerDiscovery.PhonePeer) {
        val saved = store.find(peer.id) ?: run {
            pairWith(peer)
            return
        }
        store.updateEndpoint(peer.id, peer.host, peer.port)
        store.activePeerId = peer.id
        cancelPairingInternal()
        targetActive = true
        advertiser.setPeerClientActive(true)
        _state.value = _state.value.copy(
            isPairing = false,
            pin = null,
            pairingPeerName = null,
            pairingStatus = "Paired with ${peer.name}",
            pairingFailed = false,
            isPaired = true,
            activePeerId = peer.id,
            pairedPeerName = peer.name,
            pairedPeerIds = (store.peers().map { it.id } + peer.id).toSet(),
            isReachable = false,
            targetActive = true,
        )
        ensureHeartbeat()
    }

    /** The user pressed Cancel on the PIN dialog. */
    fun cancelPairing() {
        val target = pairTarget
        val requestId = pairRequestId
        cancelPairingInternal()
        if (target != null && requestId != null) {
            // Best effort: tell the other phone to close its PIN prompt too.
            scope.launch { runCatching { sendCancel(target, requestId) } }
        }
        _state.value = _state.value.copy(
            isPairing = false,
            pin = null,
            pairingPeerName = null,
            pairingStatus = null,
            pairingFailed = false,
        )
    }

    private fun cancelPairingInternal() {
        pairingJob?.cancel()
        pairingJob = null
        pairTarget = null
        pairRequestId = null
    }

    private suspend fun pairingLoop(initialTarget: Target, pin: String, requestId: String) {
        val deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS
        var sawWrongPin = false

        while (!stopped) {
            val target = liveTarget(initialTarget)
            val attempt = try {
                attemptPair(target, pin, requestId)
            } catch (e: Exception) {
                Log.d(TAG, "Pair attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (attempt != null) {
                // A blocked socket read can outlive a Cancel press. Do not let
                // that late success pair a device the user just walked away
                // from.
                if (!currentCoroutineContext().isActive) return
                val verdict = attempt.verdict
                when {
                    verdict.isPaired -> {
                        completePairing(target, verdict.tokenHex, attempt.fingerprint)
                        return
                    }

                    verdict.type == "locked" -> {
                        val minutes = if (verdict.retryAfterMs > 0) {
                            ((verdict.retryAfterMs + 59_999) / 60_000).coerceAtLeast(1)
                        } else {
                            5
                        }
                        failPairing("Too many wrong PINs. Try again in about $minutes min.")
                        return
                    }

                    verdict.reason == "no pin entered" -> {
                        if (!sawWrongPin) {
                            _state.value = _state.value.copy(
                                pairingStatus =
                                    "Waiting for ${target.name} — enter this PIN there.",
                                pairingFailed = false,
                            )
                        }
                    }

                    verdict.reason == "wrong pin" -> {
                        sawWrongPin = true
                        _state.value = _state.value.copy(
                            pairingStatus =
                                "Wrong PIN — check it and press Submit on ${target.name} again.",
                            pairingFailed = true,
                        )
                    }

                    verdict.reason == "declined" -> {
                        failPairing("Pairing was declined on ${target.name}.")
                        return
                    }

                    verdict.reason == "not the selected device" -> {
                        failPairing("${target.name} is busy pairing with another device.")
                        return
                    }

                    else -> {
                        failPairing("Couldn't pair: ${verdict.reason ?: verdict.type}")
                        return
                    }
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                // The other phone is still showing its PIN prompt. Tell it to
                // close so a timed-out session does not leave a dead dialog
                // waiting forever. Best effort; the pairing job is ending.
                scope.launch { runCatching { sendCancel(liveTarget(initialTarget), requestId) } }
                failPairing("Pairing timed out. Move both phones closer and try again.")
                return
            }
            delay(PAIR_RETRY_MS)
        }
    }

    private fun completePairing(target: Target, tokenHex: String?, fingerprint: String) {
        val hex = tokenHex ?: run {
            failPairing("The other phone sent no pairing token.")
            return
        }
        val token = runCatching { PairingCrypto.fromHex(hex) }.getOrNull()
        if (token == null || token.size != 32) {
            failPairing("The other phone sent an invalid pairing token.")
            return
        }

        store.save(
            PairedPeer(
                id = target.id,
                name = target.name,
                tokenHex = hex,
                fingerprint = fingerprint,
                host = target.host,
                port = target.port,
            ),
        )
        store.activePeerId = target.id
        cancelPairingInternal()
        targetActive = true
        advertiser.setPeerClientActive(true)

        _state.value = _state.value.copy(
            isPairing = false,
            pin = null,
            pairingPeerName = null,
            pairingStatus = "Paired with ${target.name}",
            pairingFailed = false,
            isPaired = true,
            activePeerId = target.id,
            pairedPeerName = target.name,
            pairedPeerIds = store.peers().map { it.id }.toSet(),
            isReachable = true,
            targetActive = true,
        )
        ensureHeartbeat()
    }

    private fun failPairing(message: String) {
        cancelPairingInternal()
        _state.value = _state.value.copy(
            isPairing = false,
            pin = null,
            pairingPeerName = null,
            pairingStatus = message,
            pairingFailed = true,
        )
    }

    private fun attemptPair(target: Target, pin: String, requestId: String): PairAttempt? {
        connect(target).use { connection ->
            val salt = PeerProtocol.parseChallengeSalt(
                FileTransfer.readLine(connection.input),
            ) ?: return null

            // Same math as the server: PBKDF2 + HMAC over salt||fingerprint.
            val proof = PairingCrypto.computeProof(
                pin,
                salt + connection.fingerprint.encodeToByteArray(),
            )
            val ourName = advertiser.state.value.serviceName
                ?: Build.MODEL
                ?: "Android"

            FileTransfer.writeLine(
                connection.output,
                PeerProtocol.pairMessage(
                    name = ourName,
                    proofHex = PairingCrypto.toHex(proof),
                    requestId = requestId,
                ),
            )
            val verdict = PeerProtocol.parseVerdict(
                FileTransfer.readLine(connection.input),
            ) ?: return null
            return PairAttempt(verdict, connection.fingerprint)
        }
    }

    private fun sendCancel(target: Target, requestId: String) {
        connect(target).use { connection ->
            if (PeerProtocol.parseChallengeSalt(FileTransfer.readLine(connection.input)) == null) {
                return
            }
            FileTransfer.writeLine(connection.output, PeerProtocol.cancelMessage(requestId))
            FileTransfer.readLine(connection.input) // best effort; ignore the reply
        }
    }

    // MARK: - heartbeat

    private fun ensureHeartbeat() {
        if (stopped) return
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch { heartbeatLoop() }
    }

    private suspend fun heartbeatLoop() {
        while (!stopped) {
            val peerId = store.activePeerId
            val peer = peerId?.let { store.find(it) }
            if (peer == null) {
                _state.value = _state.value.copy(
                    isPaired = false,
                    activePeerId = null,
                    pairedPeerName = null,
                    isReachable = false,
                )
                return
            }

            val target = liveTarget(peer)
            val pong = if (target == null) {
                null
            } else {
                try {
                    pingOnce(target, peer.tokenHex)
                } catch (e: Exception) {
                    Log.d(TAG, "Heartbeat failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }

            _state.value = _state.value.copy(
                isPaired = true,
                activePeerId = peer.id,
                pairedPeerName = peer.name,
                isReachable = pong != null,
            )

            if (pong != null) {
                remoteGestureGate = pong.gestureGate

                if (gestureMode && targetActive && pong.gesture != null) {
                    handleRemoteGesture(pong.gesture)
                }

                // Gesture mode means transfers wait for a GRAB/RELEASE match. If
                // either phone has it on, an incoming file must not slip in
                // through the heartbeat behind the user's back.
                if (targetActive && !gestureMode && !pong.gestureGate && pong.outgoing > 0) {
                    maybePull()
                }

                // With Gesture mode on, our own queue waits for a grab/release
                // instead of being pushed on sight.
                if (targetActive && !gestureMode && advertiser.outgoingCount() > 0) {
                    maybePush()
                }
            }

            delay(
                if (gestureMode || remoteGestureGate) HEARTBEAT_FAST_MS
                else HEARTBEAT_SLOW_MS,
            )
        }
    }

    private fun pingOnce(target: Target, tokenHex: String): PeerProtocol.Pong? {
        connect(target).use { connection ->
            PeerProtocol.parseChallengeSalt(FileTransfer.readLine(connection.input))
                ?: return null
            FileTransfer.writeLine(
                connection.output,
                PeerProtocol.pingMessage(tokenHex),
            )
            return PeerProtocol.parsePong(FileTransfer.readLine(connection.input))
        }
    }

    // MARK: - gestures

    /** Called when Gesture mode is toggled. */
    fun setGestureMode(enabled: Boolean) {
        if (gestureMode == enabled) return
        gestureMode = enabled
        if (!enabled) {
            matcher.reset()
            remoteGestureGate = false
            pendingSendAfterCapture = false
        }
    }

    /** Called by the camera for every GRAB/RELEASE on THIS phone. */
    fun onLocalGesture(event: HandGestureMachine.EventType) {
        if (!gestureMode || !targetActive) return
        val kind = when (event) {
            HandGestureMachine.EventType.GRAB -> PeerGestureMatcher.Kind.GRAB
            HandGestureMachine.EventType.RELEASE -> PeerGestureMatcher.Kind.RELEASE
        }
        matcher.onLocal(kind, System.currentTimeMillis())?.let(::performGestureAction)
    }

    private fun handleRemoteGesture(offer: GestureOffer) {
        if (!targetActive) return
        matcher.onRemote(offer, System.currentTimeMillis())
            ?.let(::performGestureAction)
    }

    private fun performGestureAction(action: PeerGestureMatcher.Action) {
        if (!targetActive) return
        when (action) {
            PeerGestureMatcher.Action.PULL_FROM_PEER -> maybePull()
            PeerGestureMatcher.Action.SEND_TO_PEER -> {
                // A RELEASE can beat the screenshot by a few hundred ms. Do not
                // drop the matched handshake; send the moment the PNG is ready.
                if (advertiser.peekOutgoing() == null && screenshotPending()) {
                    pendingSendAfterCapture = true
                    advertiser.reportPeerTransfer(
                        "Gesture matched — sending the screenshot as soon as it's ready…",
                    )
                } else {
                    pendingSendAfterCapture = false
                    maybePush()
                }
            }
        }
    }

    /** Called after the user queues a file, so it goes without waiting a beat. */
    fun onFileQueued() {
        if (gestureMode || !targetActive) return
        maybePush()
    }

    // MARK: - file transfer

    private fun maybePush() {
        if (!targetActive) return
        synchronized(transferLock) {
            if (pushing || stopped) return
            pushing = true
        }
        scope.launch {
            try {
                pushQueuedFile()
            } catch (e: Exception) {
                Log.e(TAG, "Push failed", e)
                advertiser.reportPeerTransfer(
                    "Failed to send: ${e.message ?: "unknown error"}",
                    failed = true,
                )
            } finally {
                synchronized(transferLock) { pushing = false }
            }
        }
    }

    private fun maybePull() {
        if (!targetActive) return
        synchronized(transferLock) {
            if (pulling || stopped) return
            pulling = true
        }
        scope.launch {
            try {
                pullFromPeer()
            } catch (e: Exception) {
                Log.e(TAG, "Pull failed", e)
                advertiser.reportPeerTransfer(
                    "Failed to receive: ${e.message ?: "unknown error"}",
                    failed = true,
                )
            } finally {
                synchronized(transferLock) { pulling = false }
            }
        }
    }

    /** Push the first queued file to the paired phone. */
    private fun pushQueuedFile() {
        val peer = activePeer() ?: return
        val file = advertiser.peekOutgoing() ?: run {
            if (gestureMode) {
                advertiser.reportPeerTransfer(
                    "Gesture matched, but no file is held. Pick a file first.",
                    failed = true,
                )
            }
            return
        }
        val target = liveTarget(peer) ?: run {
            advertiser.reportPeerTransfer("${peer.name} isn't reachable right now.", failed = true)
            return
        }

        advertiser.reportPeerTransfer(
            "Sending ${file.name} to ${peer.name}…",
            isSending = true,
        )
        AavijaSounds.transferStart()

        connect(target).use { connection ->
            if (PeerProtocol.parseChallengeSalt(FileTransfer.readLine(connection.input)) == null) {
                advertiser.reportPeerTransfer("The other phone sent a bad challenge.", failed = true)
                return
            }

            FileTransfer.writeLine(
                connection.output,
                PeerProtocol.authMessage(peer.tokenHex),
            )
            val auth = PeerProtocol.parseVerdict(FileTransfer.readLine(connection.input))
            if (auth?.type != "authenticated") {
                refuseTransfer(peer, "send", auth?.reason)
                return
            }

            val header = JSONObject()
                .put("type", "file")
                .put("filename", file.name)
                .put("size", file.size)
                .put("sha256", file.sha256Hex)
                .toString()
            FileTransfer.writeLine(connection.output, header)

            val stream = context.contentResolver.openInputStream(file.uri)
                ?: throw IOException("the file is no longer readable")
            stream.use { FileTransfer.sendBytes(it, connection.output, file.size) }

            val reply = PeerProtocol.parseVerdict(FileTransfer.readLine(connection.input))
            if (reply?.type == "received") {
                advertiser.removeOutgoing(file)
                AavijaSounds.transferComplete()
                advertiser.reportPeerTransfer(
                    "Sent ${file.name} to ${peer.name} ✓",
                    failed = false,
                )
            } else {
                advertiser.reportPeerTransfer(
                    "Failed to send ${file.name}: ${reply?.reason ?: "no reply"}",
                    failed = true,
                )
            }
        }
    }

    /** Pull the first file queued on the paired phone. */
    private fun pullFromPeer() {
        val peer = activePeer() ?: return
        val target = liveTarget(peer) ?: run {
            advertiser.reportPeerTransfer("${peer.name} isn't reachable right now.", failed = true)
            return
        }

        connect(target).use { connection ->
            if (PeerProtocol.parseChallengeSalt(FileTransfer.readLine(connection.input)) == null) {
                advertiser.reportPeerTransfer("The other phone sent a bad challenge.", failed = true)
                return
            }

            FileTransfer.writeLine(
                connection.output,
                PeerProtocol.authMessage(peer.tokenHex),
            )
            val auth = PeerProtocol.parseVerdict(FileTransfer.readLine(connection.input))
            if (auth?.type != "authenticated") {
                refuseTransfer(peer, "pull", auth?.reason)
                return
            }

            FileTransfer.writeLine(connection.output, PeerProtocol.receiveRequest())
            val headerLine = FileTransfer.readLine(connection.input)
                ?: run {
                    advertiser.reportPeerTransfer("The other phone sent no file header.", failed = true)
                    return
                }

            val idle = runCatching { JSONObject(headerLine).optString("type") }.getOrNull()
            if (idle == "idle") {
                advertiser.reportPeerTransfer("Nothing is queued on ${peer.name}.", failed = false)
                return
            }

            when (val parsed = FileTransfer.parseHeader(headerLine)) {
                is FileTransfer.HeaderResult.Bad ->
                    advertiser.reportPeerTransfer(
                        "Refused a file: ${parsed.reason}",
                        failed = true,
                    )

                is FileTransfer.HeaderResult.Ok -> receiveFile(connection, peer, parsed.header)
            }
        }
    }

    private fun receiveFile(
        connection: Connection,
        peer: PairedPeer,
        header: FileTransfer.Header,
    ) {
        advertiser.reportPeerTransfer(
            "Receiving ${header.filename} from ${peer.name}…",
            isReceiving = true,
        )
        AavijaSounds.transferStart()

        val sink = try {
            ReceivedFiles.open(context, header.filename)
        } catch (e: Exception) {
            advertiser.reportPeerTransfer(
                "Could not save ${header.filename}",
                failed = true,
            )
            return
        }

        try {
            val got = FileTransfer.receive(connection.input, sink.output, header.size)
            if (got == header.sha256Hex) {
                val uri = sink.commit()
                AavijaSounds.transferComplete()
                advertiser.onPeerFileSaved(uri, header.filename)
                advertiser.reportPeerTransfer(
                    "Saved ${header.filename} to ${ReceivedFiles.DISPLAY_FOLDER}",
                    failed = false,
                )
            } else {
                sink.abort()
                advertiser.reportPeerTransfer(
                    "Deleted ${header.filename}: hash mismatch",
                    failed = true,
                )
            }
        } catch (e: Exception) {
            sink.abort()
            advertiser.reportPeerTransfer(
                "Transfer failed: ${e.message ?: "unknown error"}",
                failed = true,
            )
        }
    }

    private fun activePeer(): PairedPeer? =
        store.activePeerId?.let { store.find(it) }

    /**
     * The other phone refused our token.
     *
     * A token dies when the other phone is reinstalled or its app data is
     * cleared. Keeping the dead pairing would leave the UI saying "paired"
     * while every transfer fails forever, so forget it and ask for a fresh
     * PIN. A mere "unknown token" cannot be fixed without the other user
     * typing a new PIN anyway.
     */
    private fun refuseTransfer(peer: PairedPeer, action: String, reason: String?) {
        if (reason == "unknown token" || reason == "missing token" || reason == "token not hex") {
            forgetStalePairing(peer)
            advertiser.reportPeerTransfer(
                "${peer.name} no longer trusts this phone — tap it to pair again.",
                failed = true,
            )
            return
        }
        advertiser.reportPeerTransfer(
            "The other phone refused the $action: ${reason ?: "unknown token"}",
            failed = true,
        )
    }

    private fun forgetStalePairing(peer: PairedPeer) {
        store.forget(peer.id)
        targetActive = false
        pendingSendAfterCapture = false
        advertiser.setPeerClientActive(false)
        matcher.reset()
        val remaining = store.peers()
        val next = remaining.firstOrNull()
        store.activePeerId = next?.id
        _state.value = _state.value.copy(
            isPaired = next != null,
            activePeerId = next?.id,
            pairedPeerName = next?.name,
            pairedPeerIds = remaining.map { it.id }.toSet(),
            isReachable = false,
            pairingStatus = "${peer.name} no longer trusts this phone — tap it to pair again.",
            pairingFailed = true,
            targetActive = false,
        )
    }

    // MARK: - endpoints / TLS

    /** Prefer the live mDNS address; fall back to the last one we saved. */
    private fun liveTarget(peer: PairedPeer): Target? {
        val live = discovery.peers.value.firstOrNull { it.id == peer.id }
        if (live != null) {
            store.updateEndpoint(peer.id, live.host, live.port)
            return Target(live.id, live.name, live.host, live.port)
        }
        val host = peer.host ?: return null
        if (peer.port <= 0) return null
        return Target(peer.id, peer.name, host, peer.port)
    }

    private fun liveTarget(target: Target): Target {
        val live = discovery.peers.value.firstOrNull { it.id == target.id }
            ?: return target
        return Target(live.id, live.name, live.host, live.port)
    }

    private fun connect(target: Target): Connection {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        socket.useClientMode = true
        socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        // Trust on first use: the peer's cert is self-signed. Authentication is
        // the PIN proof / session token, never this certificate alone.
        socket.startHandshake()

        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw IOException("the other phone presented no certificate")
        val fingerprint = PairingCrypto.fingerprint(cert.encoded)

        return Connection(
            socket = socket,
            input = BufferedInputStream(socket.inputStream),
            output = BufferedOutputStream(socket.outputStream),
            fingerprint = fingerprint,
        )
    }

    private fun refreshPairedIds() {
        val peers = store.peers()
        val active = store.activePeerId
            ?.let { id -> peers.firstOrNull { it.id == id } }
            ?: peers.firstOrNull()
        if (active != null) store.activePeerId = active.id
        _state.value = _state.value.copy(
            isPaired = active != null,
            activePeerId = active?.id,
            pairedPeerName = active?.name,
            pairedPeerIds = peers.map { it.id }.toSet(),
        )
    }

    private fun generatePin(): String {
        val value = SecureRandom().nextInt(1_000_000)
        return String.format(Locale.US, "%06d", value)
    }

    private fun newRequestId(): String =
        PairingCrypto.toHex(PairingCrypto.randomToken()).take(16)

    /** Only used by the UI to decide whether a peer row is trusted. */
    fun isPairedWith(peerId: String): Boolean = store.find(peerId) != null

    /**
     * Stop treating the paired phone as the send target, without forgetting the
     * pairing. The UI calls this when the user picks a Mac, so the single file
     * queue belongs to the Mac again.
     */
    fun pauseTarget() {
        targetActive = false
        pendingSendAfterCapture = false
        advertiser.setPeerClientActive(false)
        _state.value = _state.value.copy(targetActive = false)
    }

    /** Make the paired phone the send target again (tapping/pairing it does this). */
    fun resumeTarget() {
        if (store.activePeerId == null) return
        targetActive = true
        advertiser.setPeerClientActive(true)
        _state.value = _state.value.copy(targetActive = true)
    }
}
