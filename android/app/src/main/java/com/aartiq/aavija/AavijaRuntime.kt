package com.aartiq.aavija

import android.content.Context
import android.util.Log

/**
 * App-wide owner of the pieces that must outlive a single screen.
 *
 * Background gesture sharing needs the camera, the Bonjour listener, the phone
 * peer connector, and the screenshot pipeline to keep running after
 * [MainActivity] goes to the background. Before this class, all of them were
 * created inside the activity and stopped in `onStop()`, so turning the screen
 * off (or switching apps) silently killed gesture mode.
 *
 * There is exactly one runtime per process. The activity borrows it, and
 * [GestureShareService] keeps it alive. Nothing here is static state that
 * survives process death; when Android kills the process the whole session
 * ends, which is the safe outcome for a camera + screen-capture feature.
 */
class AavijaRuntime private constructor(context: Context) {

    companion object {
        private const val TAG = "AavijaRuntime"

        @Volatile
        private var instance: AavijaRuntime? = null

        /** One runtime per process, shared by the activity and the service. */
        fun get(context: Context): AavijaRuntime =
            instance ?: synchronized(this) {
                instance ?: AavijaRuntime(context.applicationContext).also { instance = it }
            }
    }

    val advertiser = BonjourAdvertiser(context)
    val macDiscovery = MacDiscovery(context)
    val peerDiscovery = PeerDiscovery(context, DeviceIdentity.id(context)) {
        advertiser.state.value.port
    }
    val peerConnector = PeerConnector(context, advertiser, peerDiscovery)
    val gestureCamera = GestureCameraController(context)
    val screenCapture = ScreenCaptureController(context)

    private val lock = Any()

    @Volatile
    private var networkStarted = false

    @Volatile
    var uiVisible = false

    @Volatile
    private var serviceActive = false

    @Volatile
    private var gestureSessionActive = false

    /** A received image waiting to be shown when the UI returns. */
    val pendingReceivedImage = kotlinx.coroutines.flow.MutableStateFlow<BonjourAdvertiser.ReceivedImage?>(null)

    /**
     * The newest local gesture, so a screenshot captured on GRAB is not
     * reported to the peer after the user has already RELEASEd.
     */
    @Volatile
    private var latestLocalGesture: HandGestureMachine.EventType? = null

    init {
        AavijaSounds.init(context)
        gestureCamera.onEvent = { event ->
            // The controller already posts this on the main thread.
            onLocalGesture(event)
        }
        peerConnector.setScreenshotPendingProvider { screenCapture.isCapturing() }
    }

    // MARK: - lifecycle

    /** The activity is visible again. */
    fun onUiStarted() {
        uiVisible = true
        ensureNetworkStarted()
        // Reattach to a gesture session that the service kept alive while the
        // activity was gone (the camera never stopped).
        if (gestureSessionActive) {
            advertiser.setGestureMode(true)
            peerConnector.setGestureMode(true)
        }
    }

    /** The activity went to the background. Keep everything when the service runs. */
    fun onUiStopped() {
        uiVisible = false
        if (!serviceActive) stopNetworkIfIdle()
    }

    /** [GestureShareService] started: keep scanning and stay reachable. */
    fun onServiceStarted() {
        serviceActive = true
        ensureNetworkStarted()
        startGestureSession()
    }

    /** [GestureShareService] stopped (user turned Gesture mode off). */
    fun onServiceStopped() {
        serviceActive = false
        stopGestureSession()
        if (!uiVisible) stopNetworkIfIdle()
    }

    private fun ensureNetworkStarted() {
        synchronized(lock) {
            if (networkStarted) return
            networkStarted = true
        }
        try {
            advertiser.start()
            macDiscovery.start()
            peerDiscovery.start()
            peerConnector.start()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start networking", e)
        }
    }

    private fun stopNetworkIfIdle() {
        if (uiVisible || serviceActive) return
        synchronized(lock) {
            if (!networkStarted) return
            networkStarted = false
        }
        try {
            peerConnector.stop()
            peerDiscovery.stop()
            macDiscovery.stop()
            advertiser.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop networking cleanly", e)
        }
    }

    // MARK: - gestures

    private fun startGestureSession() {
        synchronized(lock) {
            if (gestureSessionActive) return
            gestureSessionActive = true
        }
        advertiser.setGestureMode(true)
        peerConnector.setGestureMode(true)
        gestureCamera.start()
    }

    private fun stopGestureSession() {
        synchronized(lock) {
            if (!gestureSessionActive) {
                // Still make sure a stale camera/session is released.
                gestureCamera.release()
                screenCapture.stop()
                return
            }
            gestureSessionActive = false
        }
        latestLocalGesture = null
        advertiser.setGestureMode(false)
        peerConnector.setGestureMode(false)
        gestureCamera.release()
        screenCapture.stop()
        advertiser.discardScreenshots()
    }

    /**
     * Every camera source (normal preview, blackout mode, future surfaces)
     * funnels through here so GRAB/RELEASE always do the same thing:
     *
     *  - GRAB  = hold what is on screen. If nothing is held, capture a
     *            screenshot and stage it for the peer's RELEASE.
     *  - RELEASE = let the matcher trigger a send/pull.
     *
     * The wire GRAB is delayed until the screenshot is staged, so the other
     * device cannot release first and pull an empty queue.
     */
    fun onLocalGesture(type: HandGestureMachine.EventType) {
        latestLocalGesture = type

        // The local matcher must see the gesture immediately.
        peerConnector.onLocalGesture(type)

        // When a user-selected file is staged in the queue:
        //  - GRAB (✊) locks it in hand (without capturing a screenshot)
        //  - RELEASE (🖐) performs DIRECT RELEASE beam to target
        val staged = advertiser.peekOutgoing()
        val isStagedNonScreenshot = staged != null && !staged.isScreenshot

        if (isStagedNonScreenshot) {
            when (type) {
                HandGestureMachine.EventType.GRAB -> {
                    advertiser.markFileGrabbed(true)
                    AavijaSounds.screenshot() // Audio feedback on grab
                    advertiser.onGesture(HandGestureMachine.EventType.GRAB)
                    advertiser.reportStatus("✊ ${staged.name} held in hand — Aim at device & RELEASE 🖐 to beam!")
                    return
                }
                HandGestureMachine.EventType.RELEASE -> {
                    advertiser.markFileGrabbed(false)
                    advertiser.onGesture(HandGestureMachine.EventType.RELEASE)
                    triggerDirectReleaseTransfer(staged)
                    return
                }
            }
        }

        if (type == HandGestureMachine.EventType.GRAB && shouldCaptureScreenshot()) {
            advertiser.reportStatus("Capturing screenshot…")
            val started = screenCapture.capture { screenshot, error ->
                if (screenshot != null && gestureSessionActive) {
                    val queued = advertiser.queueScreenshot(
                        file = screenshot.file,
                        name = screenshot.name,
                        size = screenshot.size,
                        sha256Hex = screenshot.sha256Hex,
                    )
                    if (queued) {
                        AavijaSounds.screenshot()
                        peerConnector.onScreenshotStaged()
                    } else {
                        screenshot.file.delete()
                        peerConnector.onScreenshotUnavailable(
                            "Gesture matched, but the screenshot could not be queued.",
                        )
                    }
                } else {
                    screenshot?.file?.delete()
                    if (error != null) {
                        advertiser.reportStatus(error, failed = true)
                        peerConnector.onScreenshotUnavailable(error)
                    }
                }

                // If the user already released while we were capturing, do not
                // report a stale GRAB afterwards. Their RELEASE is still on the
                // wire and can complete a handshake.
                if (latestLocalGesture == HandGestureMachine.EventType.GRAB) {
                    advertiser.onGesture(HandGestureMachine.EventType.GRAB)
                }
            }
            if (started) return
            // Capture could not start (already busy / projection gone). Fall
            // through and report the GRAB normally so receiving still works.
            peerConnector.onScreenshotUnavailable("Couldn't capture the screen.")
        }

        advertiser.onGesture(type)
    }

    private fun shouldCaptureScreenshot(): Boolean {
        if (!gestureSessionActive) return false
        if (!hasGestureTarget()) return false
        if (advertiser.state.value.isSending) return false
        // A file the user picked is already held: send that instead of
        // replacing it with a screenshot (the Mac behaves the same way).
        if (!advertiser.canHoldScreenshot()) return false
        if (!screenCapture.active.value) {
            advertiser.reportStatus(
                "Screen capture is off — enable it in Gesture Mode to grab screenshots.",
                failed = true,
            )
            return false
        }
        return true
    }

    private fun hasGestureTarget(): Boolean {
        val ad = advertiser.state.value
        val peer = peerConnector.state.value
        return ad.isPaired || ad.phoneTargetActive || (peer.isPaired && peer.targetActive)
    }

    /**
     * Unpairs a specific device or all devices if nameOrId is null.
     */
    fun unpairDevice(nameOrId: String? = null) {
        advertiser.unpairDevice(nameOrId)
        if (nameOrId == null) {
            peerConnector.unpairAllPeers()
        } else {
            val matching = peerConnector.getPairedPeers().find {
                it.id == nameOrId || it.name.equals(nameOrId, ignoreCase = true) || nameOrId.contains(it.name, ignoreCase = true)
            }
            if (matching != null) {
                peerConnector.unpairPeer(matching.id)
            }
        }
    }

    /**
     * Triggered by mid-air RELEASE (🖐) gesture or on-screen Direct Release button.
     */
    fun triggerDirectRelease() {
        val staged = advertiser.peekOutgoing() ?: return
        triggerDirectReleaseTransfer(staged)
    }

    private fun triggerDirectReleaseTransfer(file: OutgoingFile) {
        val adState = advertiser.state.value
        val peerState = peerConnector.state.value

        if (peerState.targetActive || adState.phoneTargetActive) {
            advertiser.reportPeerTransfer(
                "Direct Release 🖐: Sending ${file.name}…",
                isSending = true,
            )
            peerConnector.triggerDirectPush()
        } else if (adState.isPaired || adState.selectedMac != null) {
            advertiser.triggerDirectRelease()
        } else {
            val pairedPeers = peerConnector.getPairedPeers()
            if (pairedPeers.isNotEmpty()) {
                peerConnector.resumeTarget()
                advertiser.reportPeerTransfer(
                    "Direct Release 🖐: Sending ${file.name} to ${pairedPeers.first().name}…",
                    isSending = true,
                )
                peerConnector.triggerDirectPush()
            } else {
                advertiser.triggerDirectRelease()
                advertiser.reportStatus("🖐 Direct Release armed: Tap or aim at a device to beam ${file.name}!")
            }
        }
    }

    /**
     * Snapshot of the entire live runtime for Gemini AI assistant and diagnostics.
     */
    fun getDeviceSnapshot(): DeviceSnapshot {
        val adState = advertiser.state.value
        val peerState = peerConnector.state.value
        val macList = macDiscovery.macs.value
        val phoneList = peerDiscovery.peers.value
        val pairedPeers = peerConnector.getPairedPeers()
        val isCam = gestureCamera.isRunning
        val isHoldingItem = advertiser.outgoingCount() > 0

        return DeviceSnapshot(
            isAdvertising = adState.isAdvertising,
            localPort = adState.port,
            isPaired = adState.isPaired || peerState.isPaired,
            pairedMacName = adState.pairedMacName,
            pairedPhoneName = adState.pairedPhoneName ?: peerState.pairedPeerName,
            selectedMac = adState.selectedMac,
            phoneTargetActive = adState.phoneTargetActive,
            discoveredMacs = macList,
            discoveredPhones = phoneList,
            pairedPeers = pairedPeers,
            isCameraRunning = isCam,
            cameraFps = if (isCam) 30 else 0,
            activeMode = if (isHoldingItem) "Send Mode (Holding file/screenshot to throw)" else "Receive Mode (Listening for gestures)",
            lastPairingStatus = adState.lastPairing ?: peerState.pairingStatus,
        )
    }
}
