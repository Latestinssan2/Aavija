import AppKit
import Foundation
import Network
import Security
import SwiftUI
import CryptoKit

/// Dials out to a phone and PAIRS with it — over the encrypted (TLS) connection.
///
/// In our SWAPPED architecture the phone is the listener (it has the open port),
/// so the Mac is the one that *connects*. The handshake, in plain words:
///
///  1. We open a TLS connection. During the handshake the phone shows its
///     self-signed certificate; we grab its fingerprint (SHA-256) and stash it.
///  2. The phone sends a challenge (a random salt).
///  3. We answer with a proof that we know the 6-digit PIN, computed over the
///     salt + the fingerprint we saw. A man-in-the-middle that swapped in its
///     own cert would get a different fingerprint, so its proof won't match.
///  4. The phone replies `paired` (with a session token), `rejected`, or
///     `locked`. On success we store the token + pinned fingerprint so every
///     future connection is recognized without the PIN.
@MainActor
final class Connector: ObservableObject {

    enum State: Equatable {
        case idle
        case connecting
        case connected
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    /// The 6-digit PIN to show the user while pairing. nil when not pairing.
    @Published private(set) var pin: String?

    /// Human-readable result of the last pairing attempt.
    @Published private(set) var pairingStatus: String?

    /// Phase 4a: progress/result of the last file send. nil = nothing sent yet.
    @Published private(set) var transferStatus: String?

    /// Phase 8: true while bytes are moving in either direction. Drives the
    /// orb overlay; false at rest, so the panel stays quiet.
    @Published private(set) var isTransferring = false

    /// Copy the file we're sending so later steps can name it in the result.
    private var sendingName: String?
    /// SHA-256 computed off the main thread before the send began.
    private var sendingSHA: String?

    /// Phase 4b: true while a pull from the phone is in flight, so the
    /// heartbeat can't start a second one on top of it.
    private var isFetching = false

    // MARK: - Phase 7: gesture sharing

    /// A file the user picked but did NOT send yet. Gesture mode holds it until
    /// this Mac grabs and the phone releases.
    private struct StagedFile {
        let name: String
        let data: Data
        let sha256: String
    }

    private var stagedFile: StagedFile?

    /// Was the held item produced by the automatic screen capture? If so, a
    /// fresh GRAB should replace it with a new screenshot. A file the user
    /// explicitly chose is kept until it is sent.
    private var stagedIsScreenshot = false

    /// Name of the held file, so the panel can show it. nil = nothing held.
    @Published private(set) var stagedFileName: String?

    /// Screenshot sharing: a capture is running in the background. Shown in the
    /// panel so a grab with nothing held never feels like nothing happened.
    @Published private(set) var isCapturing = false

    /// Screenshot target mode: either entire display or active application window.
    @Published var screenshotMode: ScreenshotMode = .entireScreen

    /// Why the last automatic screenshot failed (usually missing Screen
    /// Recording permission). nil after a success.
    @Published private(set) var captureError: String?

    /// A release arrived while the screenshot was still being taken. Once the
    /// capture finishes, send it immediately instead of waiting for another
    /// gesture — the user already completed the handshake.
    private var pendingSendAfterCapture = false

    /// Pairs this Mac's own gestures with the phone's reported ones.
    private let gestureMatcher = GestureMatcher()

    /// Is Gesture mode on HERE? Used to tighten the heartbeat to 1.5 s so the
    /// ten-second match window is not eaten by network latency.
    private var localGestureMode = false

    /// The phone's `gestureGate`: when true, queued phone files must wait for a
    /// gesture. The Mac must not auto-pull them just because they exist.
    private var remoteGestureGate = false

    /// Have we successfully paired with some phone?
    @Published private(set) var isPaired: Bool

    /// Is the paired phone ACTUALLY reachable right now?
    ///
    /// A stored pairing is trust, not a live link — the two are easy to confuse
    /// and doing so is exactly the bug this exists to fix. `isReachable` comes
    /// from a real ping/pong round-trip, so with the phone's Wi-Fi off this goes
    /// false within a few seconds and the panel can say "Disconnected" instead
    /// of parroting "Paired ✓" at a phone that isn't there.
    @Published private(set) var isReachable = false

    /// The paired phone's name (from the store), so the panel can name it even
    /// while it's offline.
    @Published private(set) var pairedPhoneName: String?

    /// Where the pinned fingerprint + session token live (survives relaunch).
    private let store: PairingStore

    private var connection: NWConnection?

    /// The phone cert fingerprint captured during the TLS handshake. Written by
    /// the verify block (on the main queue) before `.ready` fires, then read to
    /// build the proof. `nonisolated(unsafe)` because the verify block is a C
    /// closure that can't be @MainActor — but we only ever touch it on main.
    nonisolated(unsafe) private var capturedFingerprint: String?

    /// The phone we're pairing with, kept so the retry loop can reconnect.
    /// nil when no pairing session is active.
    private var pairingEndpoint: NWEndpoint?

    /// When to give up retrying (the user needs time to read + type the PIN).
    private var pairingDeadline: Date?

    /// Pending retry reconnect, so we can cancel it on quit/unpair/success.
    private var retryTask: Task<Void, Never>?

    // MARK: - Phase 5b: "did the user tap me on the phone?"

    /// The phone the browser found. We keep asking it for instructions.
    ///
    /// This exists because the phone can NEVER dial us — it's the listener, and
    /// our `NWListener` is broken on this machine anyway. So the only way a tap
    /// on the phone's device list can travel Mac-ward is for US to poll. Every
    /// couple of seconds we open a TLS connection and ask a one-word question;
    /// the phone answers "show a PIN" only if the user picked our name.
    private var pollEndpoint: NWEndpoint?

    /// Pending poll timer, so it can be cancelled or replaced.
    private var pollTask: Task<Void, Never>?

    /// Have we already told the user a guess was wrong? Set so the warning
    /// stays on screen instead of being overwritten by the retry loop's
    /// friendlier "type this PIN" line 1.5 seconds later.
    private var hasSeenWrongPin = false

    /// Called by the app whenever the phone list changes.
    func phoneWasFound(_ endpoint: NWEndpoint?) {
        pollEndpoint = endpoint
        if endpoint == nil {
            pausePolling()
            // The phone vanished from mDNS. If we're paired, that's a
            // disconnection we should show at once, not 3 seconds from now.
            if isPaired { isReachable = false }
            return
        }
        if isPaired {
            // Remember the name as we SEE it. This covers pairings made before
            // we started storing the name, and picks up a rename of the phone.
            if case let .service(name, _, _, _) = endpoint, pairedPhoneName != name {
                pairedPhoneName = name
                store.pairedPhoneName = name
            }
            // Paired: the phone reappearing is a chance to confirm the link.
            startHeartbeat()
        } else {
            resumePolling()
        }
    }

    /// Ask again shortly — unless we're busy pairing, paired, or already have
    /// a poll in the air. Returning without doing anything is the point.
    private func resumePolling() {
        guard pollTask == nil, pollEndpoint != nil,
              pairingEndpoint == nil, !isPaired
        else { return }
        scheduleNextPoll()
    }

    /// Stop asking for now, but remember which phone to ask later.
    private func pausePolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Forget the phone entirely (quit / nothing found).
    func stopPolling() {
        pausePolling()
        pollEndpoint = nil
    }

    private func scheduleNextPoll() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000) // 2s between asks
            guard !Task.isCancelled else { return }
            self?.pollTask = nil
            self?.pollOnce()
        }
    }

    /// One poll: connect, ask, act on the answer.
    private func pollOnce() {
        guard let endpoint = pollEndpoint,
              pairingEndpoint == nil,   // don't collide with a live pairing
              !isPaired
        else { return }

        let tlsOptions = NWProtocolTLS.Options()
        // A poll only asks a yes/no question, so there's no fingerprint to
        // pin here — we just have to accept the phone's self-signed cert so
        // the tunnel opens. Trust is not established by a poll: nothing is
        // verified, nothing is stored, and no PIN attempt can be burned.
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { _, _, completion in completion(true) },
            .main
        )

        let connection = NWConnection(to: endpoint, using: NWParameters(tls: tlsOptions))
        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.runPoll(on: connection, to: endpoint)
                case .failed, .waiting:
                    // Can't reach the phone (or it's asleep). Drop this
                    // connection — the .cancelled that follows is what
                    // schedules the next attempt, so we never stack up two
                    // polls at once or leak a socket that's stuck retrying.
                    connection.cancel()
                case .cancelled:
                    self?.resumePolling()
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    /// Read the phone's challenge (it always speaks first), then ask our question.
    private func runPoll(on connection: NWConnection, to endpoint: NWEndpoint) {
        readLine(on: connection) { [weak self] challengeLine in
            MainActor.assumeIsolated {
                guard let self, challengeLine != nil else {
                    connection.cancel()
                    return
                }
                // The name we poll with must be the same string we BROADCAST
                // (MacAdvertiser registers the identical call), because the
                // phone matches it against the row you tapped.
                let name = MacAdvertiser.displayName()
                let message = PollMessage(name: name)
                guard let data = try? JSONEncoder().encode(message),
                      let line = String(data: data, encoding: .utf8)
                else {
                    connection.cancel()
                    return
                }
                connection.send(content: Data((line + "\n").utf8),
                                 completion: .contentProcessed { _ in
                    MainActor.assumeIsolated {
                        self.readPollAnswer(on: connection, to: endpoint)
                    }
                })
            }
        }
    }

    /// If the phone says the user tapped US, stop asking and show a PIN.
    private func readPollAnswer(on connection: NWConnection, to endpoint: NWEndpoint) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                defer { connection.cancel() }
                guard let self, let line,
                      let reply = try? JSONDecoder().decode(
                          PollResponse.self, from: Data(line.utf8)
                      )
                else { return }

                if reply.action == "pair" {
                    // Someone picked this Mac on their phone. Generate a PIN,
                    // put it on screen, and let the normal retry loop verify
                    // whatever they type.
                    self.pausePolling()
                    self.pair(with: endpoint)
                }
            }
        }
    }

    // MARK: - Is the paired phone actually there? (heartbeat)

    /// How often to check the link, and how long to wait for an answer.
    private static let heartbeatEvery: UInt64 = 3_000_000_000
    private static let heartbeatTimeout: UInt64 = 4_000_000_000

    private var heartbeatTask: Task<Void, Never>?
    private var heartbeatConnection: NWConnection?

    /// With gesture mode running on either side, check twice as often. The
    /// match window is ten seconds; a three-second heartbeat would leave the
    /// user as little as seven seconds to complete the gesture.
    private var heartbeatInterval: UInt64 {
        (localGestureMode || remoteGestureGate) ? 1_500_000_000 : Self.heartbeatEvery
    }

    /// Start (or keep) the reachability loop. Safe to call repeatedly.
    private func startHeartbeat() {
        guard isPaired else { return }
        if heartbeatTask != nil || heartbeatConnection != nil { return }
        heartbeatOnce() // check NOW rather than after the first interval
    }

    private func scheduleNextHeartbeat() {
        guard isPaired else { return }
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: self.heartbeatInterval)
            guard !Task.isCancelled else { return }
            self.heartbeatTask = nil
            self.heartbeatOnce()
        }
    }

    /// Stop the loop and forget the verdict (on unpair / quit / while pairing).
    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        heartbeatConnection?.cancel()
        heartbeatConnection = nil
        isReachable = false
    }

    /// One check: dial the phone, send `ping`, expect `pong`.
    private func heartbeatOnce() {
        guard isPaired, pairingEndpoint == nil else { return }
        guard let endpoint = pollEndpoint else {
            // We can't even see the phone on the Wi-Fi. Keep ticking so we
            // recover the moment it reappears.
            isReachable = false
            scheduleNextHeartbeat()
            return
        }

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { _, _, completion in completion(true) },
            .main
        )

        let connection = NWConnection(to: endpoint, using: NWParameters(tls: tlsOptions))
        heartbeatConnection = connection

        // A watchdog, because a peer that never answers would otherwise leave
        // this attempt hanging forever and the status stuck on "connected".
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.heartbeatTimeout)
            guard let self, self.heartbeatConnection === connection else { return }
            connection.cancel()
            self.heartbeatConnection = nil
            self.isReachable = false
            self.scheduleNextHeartbeat()
        }

        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.runHeartbeat(on: connection)
                case .failed, .waiting:
                    // Can't reach the phone right now.
                    self?.finishHeartbeat(connection, reachable: false)
                case .cancelled:
                    break
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    /// Read the challenge the phone always sends first, then ask our question.
    private func runHeartbeat(on connection: NWConnection) {
        readLine(on: connection) { [weak self] challengeLine in
            MainActor.assumeIsolated {
                guard let self, challengeLine != nil else {
                    self?.finishHeartbeat(connection, reachable: false)
                    return
                }
                guard let line = Self.encodeLine(
                    PingMessage(token: self.store.sessionToken.map(Pairing.hex))
                ) else {
                    self.finishHeartbeat(connection, reachable: false)
                    return
                }
                connection.send(content: Data((line + "\n").utf8),
                                completion: .contentProcessed { _ in
                    MainActor.assumeIsolated {
                        self.readHeartbeat(on: connection)
                    }
                })
            }
        }
    }

    /// A `pong` means the phone is there and speaking our protocol.
    private func readHeartbeat(on connection: NWConnection) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                guard let self else { return }
                let ok: Bool
                var outgoing = 0
                var gestureGate = false
                var offer: GestureOffer?
                if let line, let obj = Self.decodeObject(line) {
                    ok = (obj["type"] as? String) == "pong"
                    if let o = obj["outgoing"] as? Int {
                        outgoing = o
                    } else if let o = obj["outgoing"] as? NSNumber {
                        outgoing = o.intValue
                    }
                    if let g = obj["gestureGate"] as? Bool {
                        gestureGate = g
                    } else if let g = obj["gestureGate"] as? NSNumber {
                        gestureGate = g.boolValue
                    }
                    // Phase 7: the phone's latest gesture, with an age measured
                    // on ITS clock so no clock sync is needed here.
                    if let kindString = obj["gesture"] as? String,
                       let kind = GestureOffer.Kind(rawValue: kindString) {
                        let id = (obj["gestureId"] as? NSNumber)?.int64Value ?? 0
                        let ageMs = (obj["gestureAgeMs"] as? NSNumber)?.doubleValue ?? -1
                        if ageMs >= 0, ageMs <= GestureMatcher.window * 1000 {
                            offer = GestureOffer(kind: kind, id: id, ageMs: ageMs)
                        }
                    }
                } else {
                    ok = false
                }
                self.remoteGestureGate = gestureGate
                self.finishHeartbeat(
                    connection,
                    reachable: ok,
                    hasOutgoing: outgoing > 0,
                    gestureGate: gestureGate,
                    offer: offer
                )
            }
        }
    }

    /// One attempt is over. Whoever gets here first (the answer or the
    /// watchdog) clears `heartbeatConnection`, so the other one stands down and
    /// we never schedule two loops.
    private func finishHeartbeat(
        _ connection: NWConnection,
        reachable: Bool,
        hasOutgoing: Bool = false,
        gestureGate: Bool = false,
        offer: GestureOffer? = nil
    ) {
        guard heartbeatConnection === connection else { return }
        heartbeatConnection = nil
        connection.cancel()
        isReachable = reachable
        scheduleNextHeartbeat()
        guard reachable else { return }

        // Phase 7: a gesture that matches one of ours starts the transfer.
        if let offer {
            handleRemoteGesture(offer)
        }

        // Phase 4b behaviour, unless the phone is in Gesture mode: then the
        // queue must wait for a grab/release handshake, not be pulled on sight.
        if hasOutgoing && !gestureGate {
            fetchOutgoingFile()
        }
    }

    // MARK: - Phase 7: matching gestures

    /// Gesture mode was switched on/off in the panel.
    func setLocalGestureMode(_ on: Bool) {
        guard localGestureMode != on else { return }
        localGestureMode = on
        gestureMatcher.reset()

        if on {
            Task {
                _ = await ScreenRecordingPermission.shared.requestIfNeeded()
            }
        } else {
            // No reason to keep a private file's bytes in memory once gesture
            // sharing is off.
            pendingSendAfterCapture = false
            clearStagedFile()
            GestureEyeModel.shared.hide()
        }

        // Apply the new heartbeat rate immediately rather than after the old
        // three-second tick.
        if isPaired {
            heartbeatTask?.cancel()
            heartbeatTask = nil
            if heartbeatConnection == nil {
                heartbeatOnce()
            }
        }
    }

    /// A gesture was detected on this Mac.
    func handleLocalGesture(_ type: HandGestureMachine.EventType) {
        let kind: GestureOffer.Kind = (type == .grab) ? .grab : .release

        // Phase 9: the notch eye is the visible twin of this state machine.
        // GRAB shuts it with a soft lid sound; RELEASE wakes it with a chime.
        if kind == .grab {
            GestureEyeModel.shared.grab()
        } else {
            GestureEyeModel.shared.release()
        }

        let action = gestureMatcher.onLocal(kind, at: CACurrentMediaTime())

        // Grabbing with nothing held means "share what I'm looking at": take a
        // screenshot right now and keep it staged, ready for the phone's
        // release. If a file is already held, respect that choice instead.
        if kind == .grab, localGestureMode, isPaired,
           stagedFile == nil || stagedIsScreenshot {
            captureScreenshot(fromGrab: true)
        }

        if let action {
            performGestureAction(action)
        }
    }

    /// The phone reported a gesture on its heartbeat.
    private func handleRemoteGesture(_ offer: GestureOffer) {
        // The phone repeats its latest gesture on every heartbeat. Only the
        // first report should move the eye (and play its sound).
        if gestureMatcher.isNew(offer) {
            switch offer.kind {
            case .grab: GestureEyeModel.shared.grab()
            case .release: GestureEyeModel.shared.release()
            }
        }

        guard let action = gestureMatcher.onRemote(offer, at: CACurrentMediaTime()) else { return }
        performGestureAction(action)
    }

    private func performGestureAction(_ action: GestureMatcher.Action) {
        switch action {
        case .pullFromPhone:
            transferStatus = "Gesture matched — pulling from your phone…"
            fetchOutgoingFile()
        case .sendToPhone:
            sendStagedFile()
        }
    }

    /// Hold a file without sending it, so a gesture can release it later.
    func chooseFileToHold() {
        NSApp.activate(ignoringOtherApps: true)

        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Hold"
        panel.message = "Choose a file for gesture sharing"

        panel.begin { [weak self] response in
            MainActor.assumeIsolated {
                guard response == .OK, let url = panel.url else { return }
                self?.stageFile(at: url)
            }
        }
    }

    private func stageFile(at url: URL) {
        transferStatus = "Reading \(url.lastPathComponent)…"
        Task.detached(priority: .userInitiated) { [weak self] in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { try Data(contentsOf: url) }
            await MainActor.run {
                guard let self else { return }
                switch result {
                case .success(let data):
                    guard data.count <= Self.maxFileBytes else {
                        self.transferStatus = "File too large (max 200 MB)."
                        return
                    }
                    let sha = Self.sha256Hex(data)
                    self.stagedFile = StagedFile(
                        name: url.lastPathComponent,
                        data: data,
                        sha256: sha
                    )
                    self.stagedIsScreenshot = false
                    self.stagedFileName = url.lastPathComponent
                    self.transferStatus =
                        "Holding \(url.lastPathComponent) — GRAB here, then RELEASE on your phone within 10 s."
                case .failure(let error):
                    self.transferStatus = "Couldn't read that file: \(error.localizedDescription)"
                }
            }
        }
    }

    /// The manual equivalent of "GRAB with nothing held". Useful before a
    /// gesture (capture now, then GRAB + RELEASE to send) and for checking
    /// that Screen Recording permission is granted.
    func captureScreenNow(mode: ScreenshotMode? = nil) {
        if let mode = mode {
            self.screenshotMode = mode
        }
        guard isPaired else {
            transferStatus = "Pair with your phone first."
            return
        }
        guard localGestureMode else {
            transferStatus = "Turn on Gesture mode first."
            return
        }
        Task {
            _ = await ScreenRecordingPermission.shared.requestIfNeeded()
            captureScreenshot(mode: self.screenshotMode)
        }
    }

    /// Capture either display or active app and stage it as if the user had picked it.
    ///
    /// The PNG never touches disk: it lives in memory until the phone's release
    /// sends it (or gesture mode ends, which frees it).
    private func captureScreenshot(mode: ScreenshotMode? = nil, fromGrab: Bool = false) {
        guard !isCapturing else { return }
        let targetMode = mode ?? self.screenshotMode
        isCapturing = true
        captureError = nil
        transferStatus = targetMode == .activeApp ? "Capturing active app…" : "Capturing your screen…"
        GestureEyeModel.shared.capturing()

        Task { @MainActor in
            do {
                let result = try await MacScreenshot.capture(mode: targetMode)
                let data = result.data
                let name = result.filename

                // The user may have switched gesture mode off while the capture
                // ran. Don't silently keep their screen in memory after that.
                guard localGestureMode, isPaired else {
                    isCapturing = false
                    GestureEyeModel.shared.hide()
                    return
                }
                guard data.count <= Self.maxFileBytes else {
                    isCapturing = false
                    captureError = "The screenshot is larger than the 200 MB limit."
                    transferStatus = captureError
                    pendingSendAfterCapture = false
                    GestureEyeModel.shared.failure("Screenshot is too large")
                    return
                }

                stagedFile = StagedFile(
                    name: name,
                    data: data,
                    sha256: Self.sha256Hex(data)
                )
                stagedIsScreenshot = true
                stagedFileName = name
                isCapturing = false
                captureError = nil

                CaptureEffectPanel.shared.show()
                AavijaSound.shared.screenshot()
                transferStatus =
                    "\(targetMode == .activeApp ? "Active app" : "Screenshot") captured — RELEASE on your phone within 10 s."

                if pendingSendAfterCapture {
                    pendingSendAfterCapture = false
                    sendStagedFile()
                } else if fromGrab {
                    // The hand is still closed: keep the eye shut, ready for
                    // the phone's RELEASE.
                    GestureEyeModel.shared.holding("Screenshot ready")
                } else {
                    // Manual capture (no grab): open the eye to unveil it.
                    GestureEyeModel.shared.ready("Screenshot captured")
                }
            } catch {
                isCapturing = false
                captureError = error.localizedDescription
                transferStatus = error.localizedDescription
                pendingSendAfterCapture = false
                GestureEyeModel.shared.failure("Couldn't capture")
            }
        }
    }

    private func sendStagedFile() {
        // A release can beat the capture by a few hundred milliseconds. Don't
        // throw the gesture away: remember it and send the moment the PNG is
        // ready.
        if isCapturing {
            pendingSendAfterCapture = true
            transferStatus = "Gesture matched — sending the screenshot as soon as it's ready…"
            return
        }
        guard let staged = stagedFile else {
            transferStatus = "Gesture matched, but no file is held. GRAB with nothing held to capture your screen, or choose a file with “Hold a file for gesture…”."
            return
        }
        pendingSendAfterCapture = false
        beginSend(data: staged.data, name: staged.name, sha256: staged.sha256)
    }

    func clearStagedFile() {
        stagedFile = nil
        stagedIsScreenshot = false
        stagedFileName = nil
    }

    // MARK: - Phase 4b: fetch a file from the paired phone (phone → Mac)
    private final class DataBox: @unchecked Sendable {
        var data = Data()
    }

    private func fetchOutgoingFile() {
        // The heartbeat runs every ~3 s. Without this guard a slow transfer
        // would start a second (third, …) pull on top of itself.
        guard !isFetching else { return }
        guard let endpoint = pollEndpoint, let token = store.sessionToken else { return }
        isFetching = true
        isTransferring = true
        AavijaSound.shared.transferStart()
        GestureEyeModel.shared.sharing("Pulling from your phone…")
        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, { _, _, c in c(true) }, .main)
        let connection = NWConnection(to: endpoint, using: NWParameters(tls: tlsOptions))
        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.runFetch(on: connection, token: token)
                case .cancelled, .failed:
                    self?.isFetching = false
                    self?.isTransferring = false
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    /// Every exit from a pull funnels through here, so one bad attempt can never
    /// wedge the queue as "always fetching".
    private func finishFetch(on connection: NWConnection) {
        isFetching = false
        isTransferring = false
        connection.cancel()
    }

    private func runFetch(on connection: NWConnection, token: Data) {
        readLine(on: connection) { [weak self] challengeLine in
            MainActor.assumeIsolated {
                guard let self else { return }
                guard challengeLine != nil else {
                    self.finishFetch(on: connection)
                    return
                }
                guard let line = Self.encodeLine(AuthMessage(token: Pairing.hex(token))) else {
                    self.finishFetch(on: connection)
                    return
                }
                connection.send(content: Data((line + "\n").utf8), completion: .contentProcessed { _ in
                    MainActor.assumeIsolated {
                        self.readFetchAuth(on: connection)
                    }
                })
            }
        }
    }

    private func readFetchAuth(on connection: NWConnection) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                guard let self else { return }
                guard let line, let obj = Self.decodeObject(line),
                      (obj["type"] as? String) == "authenticated" else {
                    self.finishFetch(on: connection)
                    return
                }
                guard let rl = Self.encodeLine(ReceiveRequestMsg()) else {
                    self.finishFetch(on: connection)
                    return
                }
                connection.send(content: Data((rl + "\n").utf8), completion: .contentProcessed { _ in
                    MainActor.assumeIsolated {
                        self.readFetchResponse(on: connection)
                    }
                })
            }
        }
    }

    private func readFetchResponse(on connection: NWConnection) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                guard let self else { return }
                guard let line else {
                    self.finishFetch(on: connection)
                    return
                }
                if let obj = Self.decodeObject(line), (obj["type"] as? String) == "idle" {
                    self.finishFetch(on: connection)
                    return
                }
                guard let hdr = try? JSONDecoder().decode(FileHeaderRecv.self, from: Data(line.utf8)) else {
                    self.finishFetch(on: connection)
                    return
                }
                self.readFetchBody(on: connection, header: hdr)
            }
        }
    }

    private func readFetchBody(on connection: NWConnection, header: FileHeaderRecv) {
        self.transferStatus = "Receiving \(header.filename)…"
        let box = DataBox()
        func recvMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
                MainActor.assumeIsolated {
                    if let data = data {
                        box.data.append(data)
                    }
                    if box.data.count >= header.size {
                        let payload = box.data.prefix(header.size)
                        let res = FileReceiver.write(name: header.filename, size: header.size, sha256: header.sha256, data: Data(payload))
                        switch res {
                        case .ok(let path):
                            self.transferStatus = "Saved \(header.filename) to \(path)"
                            AavijaSound.shared.transferComplete()
                            GestureEyeModel.shared.delivered("Saved \(header.filename)")
                        case .failed(let reason):
                            self.transferStatus = "Received \(header.filename) but failed: \(reason)"
                            GestureEyeModel.shared.failure("File failed its checksum")
                        }
                        self.finishFetch(on: connection)
                        return
                    }
                    if isComplete || error != nil {
                        // The phone closed early. Say so instead of leaving the
                        // panel stuck on "Receiving…" forever.
                        self.transferStatus = "Transfer of \(header.filename) was cut short."
                        GestureEyeModel.shared.failure("Transfer was cut short")
                        self.finishFetch(on: connection)
                        return
                    }
                    recvMore()
                }
            }
        }
        recvMore()
    }

    init(store: PairingStore = PairingStore()) {
        self.store = store
        self.isPaired = store.isPaired
        self.pairedPhoneName = store.pairedPhoneName
        // If we're already paired, start checking the link right away — a fresh
        // launch must not sit on a stale "connected" (or a stale "disconnected").
        if isPaired { heartbeatOnce() }

        // Request Screen Recording permission upfront once so macOS registers
        // Aavija in System Settings → Privacy & Security → Screen & System Audio Recording.
        Task {
            _ = await ScreenRecordingPermission.shared.requestIfNeeded()
        }
    }

    /// Begin pairing with `endpoint`: generate a PIN, show it, then keep trying
    /// until the user types it on the phone (or we time out).
    ///
    /// WHY RETRIES? When you click Pair, the Mac can't know whether you've had
    /// time to read the PIN and type it on the phone yet. So it generates the
    /// PIN once, shows it, and reconnects every ~1.5s. Each attempt is a FRESH
    /// secure handshake (new challenge salt, so replay-proof). The phone answers
    /// "no pin entered" until you actually type the PIN — then the next attempt
    /// succeeds. The PIN stays the SAME across retries so you have time to type it.
    func pair(with endpoint: NWEndpoint) {
        retryTask?.cancel()
        retryTask = nil
        connection?.cancel()
        connection = nil
        capturedFingerprint = nil
        // We're pairing with THIS phone now, so stop asking it questions —
        // and stop the reachability loop while the pairing session is live.
        pausePolling()
        stopHeartbeat()
        // Fresh session: any "wrong PIN" warning from a previous one is stale.
        hasSeenWrongPin = false

        // Fresh PIN for this attempt. Shown to the user, typed into the phone.
        self.pin = Pairing.generatePin()
        self.pairingEndpoint = endpoint
        // Generous window: time to unlock the phone, read 6 digits, and type them.
        self.pairingDeadline = Date().addingTimeInterval(120)
        self.pairingStatus = "Type this PIN on your phone…"

        attempt()
    }

    /// One connection + handshake attempt, reusing the EXISTING `pin`.
    private func attempt() {
        guard let endpoint = pairingEndpoint else { return }
        capturedFingerprint = nil
        state = .connecting

        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { [weak self] metadata, _, completion in
                // Capture the phone's cert fingerprint so the proof below is
                // bound to the REAL phone, not an impostor.
                if let fp = Pairing.peerFingerprint(from: metadata) {
                    MainActor.assumeIsolated { self?.capturedFingerprint = fp }
                }
                // Trust On First Use: accept the self-signed cert so the tunnel
                // comes up. Authentication happens via the PIN proof + later
                // fingerprint pinning — NOT by blindly trusting this cert forever.
                completion(true)
            },
            .main
        )

        let connection = NWConnection(to: endpoint, using: NWParameters(tls: tlsOptions))
        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.state = .connected
                    self?.runHandshake(on: connection)
                case .failed(let error):
                    // Couldn't even reach the phone — surface it and stop.
                    self?.state = .failed(error.localizedDescription)
                    self?.pin = nil
                    self?.pairingEndpoint = nil
                    self?.retryTask?.cancel()
                    self?.resumePolling()
                    connection.cancel()
                case .waiting(let error):
                    // .waiting = "can't reach it right now" (e.g. phone slept).
                    self?.state = .failed(error.localizedDescription)
                    self?.pin = nil
                    self?.pairingEndpoint = nil
                    self?.resumePolling()
                case .cancelled:
                    if self?.connection === connection {
                        self?.connection = nil
                        // Only drop to idle if we're NOT mid-pairing-retry.
                        if self?.pairingEndpoint == nil {
                            self?.state = .idle
                        }
                    }
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
        self.connection = connection
    }

    /// Step 2-4: read the challenge, send our proof, read the verdict.
    private func runHandshake(on connection: NWConnection) {
        guard let fingerprint = capturedFingerprint else {
            fail("Couldn't read the phone's certificate")
            connection.cancel()
            return
        }

        readLine(on: connection) { [weak self] challengeLine in
            MainActor.assumeIsolated {
                guard let self, let challengeLine,
                      let challenge = try? JSONDecoder().decode(
                          Challenge.self, from: Data(challengeLine.utf8)
                      ),
                      let salt = try? Pairing.fromHex(challenge.salt)
                else {
                    self?.fail("Phone sent a bad challenge")
                    connection.cancel()
                    return
                }

                // Build the proof bound to salt + the fingerprint we saw.
                let proof = Pairing.proof(pin: self.pin ?? "", salt: salt, fingerprint: fingerprint)
                let message = PairMessage(
                    proof: Pairing.hex(proof),
                    // Same string we broadcast and poll with — the phone uses
                    // it to check this Mac is the one the user tapped.
                    name: MacAdvertiser.displayName()
                )
                self.sendPair(message, on: connection)
            }
        }
    }

    /// Send the `pair` message, then read the phone's reply.
    private func sendPair(_ message: PairMessage, on connection: NWConnection) {
        guard let data = try? JSONEncoder().encode(message),
              let line = String(data: data, encoding: .utf8)
        else {
            fail("Couldn't encode the pairing message")
            connection.cancel()
            return
        }

        connection.send(content: Data((line + "\n").utf8), completion: .contentProcessed { [weak self] error in
            MainActor.assumeIsolated {
                if let error {
                    self?.fail(error.localizedDescription)
                    connection.cancel()
                    return
                }
                self?.readVerdict(on: connection)
            }
        })
    }

    /// Read the phone's `paired` / `rejected` / `locked` reply and act on it.
    private func readVerdict(on connection: NWConnection) {
        readLine(on: connection) { [weak self] verdictLine in
            MainActor.assumeIsolated {
                guard let self, let verdictLine,
                      let verdict = try? JSONDecoder().decode(
                          PairResponse.self, from: Data(verdictLine.utf8)
                      )
                else {
                    self?.fail("Phone sent a bad reply")
                    connection.cancel()
                    return
                }

                switch verdict.type {
                case "paired":
                    // Success: remember the token + pin this phone's fingerprint.
                    if let tokenHex = verdict.token, let token = try? Pairing.fromHex(tokenHex) {
                        self.store.sessionToken = token
                    }
                    self.store.pinnedFingerprint = self.capturedFingerprint
                    // Remember WHICH phone this was, so the panel can name it
                    // later even when the phone is offline.
                    if case let .service(name, _, _, _) = self.pairingEndpoint {
                        self.store.pairedPhoneName = name
                        self.pairedPhoneName = name
                    }
                    self.isPaired = true
                    // We just completed a live exchange with this phone, so it
                    // is reachable RIGHT NOW — start honest from the truth and
                    // let the heartbeat correct it if the link drops.
                    self.isReachable = true
                    // No lingering "paired" banner: the live line below the PIN
                    // area is the single source of truth for the link state.
                    self.pairingStatus = nil
                    self.pin = nil
                    self.pairingEndpoint = nil
                    self.retryTask?.cancel()
                    self.retryTask = nil
                    self.pausePolling()
                    self.startHeartbeat()

                case "locked":
                    self.pairingStatus = "Too many wrong PINs — try again in a few minutes."
                    self.pin = nil
                    self.pairingEndpoint = nil
                    self.retryTask?.cancel()
                    self.retryTask = nil
                    self.resumePolling()

                default: // "rejected"
                    let reason = verdict.reason ?? "wrong pin"
                    if reason == "no pin entered" {
                        // The user hasn't pressed Submit on the phone yet —
                        // this is EXPECTED while they're reading the PIN. Keep
                        // retrying rather than giving up.
                        self.scheduleRetry()
                    } else if reason == "wrong pin" {
                        // Keep the SAME PIN on screen and keep trying. The phone
                        // allows five guesses, so the user must be able to
                        // retype and press Submit again without waiting for a
                        // fresh PIN — and the phone buzzes + shows "N left".
                        self.hasSeenWrongPin = true
                        self.pairingStatus = "Wrong PIN — check it and press Submit again"
                        self.scheduleRetry()
                    } else {
                        self.pairingStatus = "Rejected: \(reason)"
                        self.pin = nil
                        self.pairingEndpoint = nil
                        self.retryTask?.cancel()
                        self.retryTask = nil
                        self.resumePolling()
                    }
                }
                connection.cancel()
            }
        }
    }

    /// The phone wasn't ready (no PIN typed yet). Reconnect and try again
    /// shortly, until the user has had time to type the PIN or we time out.
    private func scheduleRetry() {
        guard let deadline = pairingDeadline, Date() < deadline else {
            pairingStatus = "Timed out waiting for the PIN. Tap your Mac on the phone to try again."
            pin = nil
            pairingEndpoint = nil
            resumePolling() // back to asking the phone whether it wants us
            return
        }
        // After a wrong guess we leave the warning up. Flipping straight back
        // to "type this PIN" would hide the fact that the guess was rejected.
        if !hasSeenWrongPin {
            pairingStatus = "Type this PIN on your phone…"
        }
        // The just-cancelled connection would leave us stuck at .connected;
        // go back to .connecting so the state doesn't lie between retries.
        state = .connecting
        retryTask?.cancel()
        // Task { } inherits the MainActor from this @MainActor class, so
        // attempt() is safe to call directly — no assumeIsolated needed.
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000) // 1.5s between tries
            guard !Task.isCancelled else { return }
            self?.attempt()
        }
    }

    /// Read one newline-terminated line. Returns nil on EOF or error.
    ///
    /// `nonisolated` because the receive handler runs on the connection's queue,
    /// not the MainActor. The accumulation buffer lives in a `@unchecked Sendable`
    /// box so Swift 6's strict concurrency doesn't flag it as a cross-actor
    /// mutation — safe here because exactly one receive is in flight at a time.
    nonisolated private func readLine(
        on connection: NWConnection,
        completion: @escaping @Sendable (String?) -> Void
    ) {
        let box = LineBox()
        func receiveMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, isComplete, error in
                if let data { box.buffer.append(data) }
                if let newline = box.buffer.firstIndex(of: UInt8(ascii: "\n")) {
                    let lineData = box.buffer.prefix(upTo: newline)
                    box.buffer.removeAll(keepingCapacity: true)
                    completion(String(data: lineData, encoding: .utf8))
                } else if isComplete || error != nil {
                    completion(nil)
                } else {
                    receiveMore() // keep accumulating until the newline arrives
                }
            }
        }
        receiveMore()
    }

    private func fail(_ message: String) {
        state = .failed(message)
        pairingStatus = message
        pin = nil
    }

    // MARK: - Phase 4a: sending a file to the paired phone

    /// The biggest file we'll send. Kept in step with the phone's
    /// `FileTransfer.MAX_FILE_BYTES` — the phone refuses anything larger, and
    /// failing HERE gives a clear message instead of a mid-transfer rejection.
    private static let maxFileBytes = 200 * 1024 * 1024
    /// Send in 1 MB chunks, exactly as the protocol doc promises.
    private static let fileChunkBytes = 1024 * 1024

    /// Send `url` to the paired phone.
    ///
    /// WHY THIS DIRECTION IS "PUSH": the phone is the listener, so the Mac is
    /// always the one that dials. A send therefore reuses the same dial we use
    /// for polling: connect, prove we hold the token (`auth`), then stream the
    /// header + bytes. The phone keeps the file only if the SHA-256 matches.
    /// Show the Mac's open panel, then send whatever file the user picks.
    ///
    /// We deliberately do NOT use SwiftUI's `.fileImporter` for this. It
    /// presents the picker as a SHEET stuck to the menu-bar window — and a
    /// menu-bar window closes the instant it resigns key, which is exactly when
    /// the picker grabs focus. The sheet dies mid-click and the picker vanishes;
    /// that was the bug. `NSOpenPanel.begin` presents the panel as its own
    /// APP-MODAL window, untied to that fragile window, and it returns
    /// immediately — so nothing blocks our one thread (the difference from the
    /// very first version, which froze everything with `runModal()`).
    func chooseAndSendFile() {
        // An accessory (menu-bar) app isn't frontmost, so bring it forward —
        // otherwise the panel can open behind other windows and never get focus.
        NSApp.activate(ignoringOtherApps: true)

        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Send"
        panel.message = "Choose a file to send to your phone"

        panel.begin { [weak self] response in
            // `begin` calls back on the main thread; tell the compiler that.
            MainActor.assumeIsolated {
                guard response == .OK, let url = panel.url else { return }
                self?.sendFile(at: url)
            }
        }
    }

    func sendFile(at url: URL) {
        guard pairingEndpoint == nil else {
            transferStatus = "Finish pairing first."
            return
        }
        guard pollEndpoint != nil else {
            transferStatus = "No phone found on the Wi-Fi yet — is its Wi-Fi on?"
            return
        }
        guard store.sessionToken != nil else {
            transferStatus = "Pair with your phone before sending."
            return
        }

        // Read + hash the file OFF the main thread. THIS is the beachball fix:
        // `Data(contentsOf:)` was running on the main actor and froze the whole
        // menu-bar app while a big file loaded. Now the panel stays responsive.
        transferStatus = "Reading \(url.lastPathComponent)…"
        Task.detached(priority: .userInitiated) { [weak self] in
            // Sandboxed app: a file the user just picked needs its SECURITY
            // SCOPE open before we may read it. (No-op if not sandboxed.)
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let result = Result { () -> (Data, String) in
                let data = try Data(contentsOf: url)
                return (data, Self.sha256Hex(data))
            }
            await MainActor.run {
                guard let self else { return }
                switch result {
                case .success(let (data, sha)):
                    self.beginSend(data: data, name: url.lastPathComponent, sha256: sha)
                case .failure(let error):
                    self.transferStatus = "Couldn't read that file: \(error.localizedDescription)"
                }
            }
        }
    }

    nonisolated private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// The file is in memory; dial the phone and push it.
    private func beginSend(data: Data, name: String, sha256: String? = nil) {
        guard data.count <= Self.maxFileBytes else {
            transferStatus = "File too large (max 200 MB)."
            return
        }
        // Re-read the endpoint/token here: time passed while the file loaded.
        guard let endpoint = pollEndpoint, let token = store.sessionToken else {
            transferStatus = "The phone isn't available right now."
            return
        }

        sendingName = name
        sendingSHA = sha256
        transferStatus = "Sending \(name)…"
        isTransferring = true
        AavijaSound.shared.transferStart()
        GestureEyeModel.shared.sharing("Sending \(name)")
        state = .connecting

        let tlsOptions = NWProtocolTLS.Options()
        // Trust on first use: the phone's cert is self-signed and is regenerated
        // when the app restarts, so we can't hard-pin it. Identity is proven the
        // OTHER way here — the phone only accepts us if we send a token it minted.
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { _, _, completion in completion(true) },
            .main
        )

        let connection = NWConnection(to: endpoint, using: NWParameters(tls: tlsOptions))
        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.state = .connected
                    self?.runSend(on: connection, token: token, data: data)
                case .failed(let error):
                    self?.state = .failed(error.localizedDescription)
                    self?.transferStatus = "Send failed: \(error.localizedDescription)"
                    self?.isReachable = false
                    self?.isTransferring = false
                    GestureEyeModel.shared.failure("Couldn't reach your phone")
                    connection.cancel()
                case .waiting(let error):
                    self?.state = .failed(error.localizedDescription)
                    self?.transferStatus = "Can't reach the phone: \(error.localizedDescription)"
                    self?.isReachable = false
                    self?.isTransferring = false
                    GestureEyeModel.shared.failure("Couldn't reach your phone")
                    connection.cancel()
                case .cancelled:
                    self?.state = .idle
                    self?.isTransferring = false
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
        self.connection = connection
    }

    /// Read the phone's challenge, then prove we're paired with the token.
    private func runSend(on connection: NWConnection, token: Data, data: Data) {
        readLine(on: connection) { [weak self] challengeLine in
            MainActor.assumeIsolated {
                guard let self, challengeLine != nil else {
                    connection.cancel()
                    return
                }
                guard let line = Self.encodeLine(AuthMessage(token: Pairing.hex(token))) else {
                    self.transferStatus = "Couldn't build the auth message."
                    connection.cancel()
                    return
                }
                connection.send(content: Data((line + "\n").utf8),
                                completion: .contentProcessed { _ in
                    MainActor.assumeIsolated {
                        self.readAuthVerdict(on: connection, data: data)
                    }
                })
            }
        }
    }

    /// The phone answers `authenticated` (we may send) or `rejected` (we may not).
    private func readAuthVerdict(on connection: NWConnection, data: Data) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                guard let self else {
                    connection.cancel()
                    return
                }
                guard let line, let obj = Self.decodeObject(line) else {
                    self.transferStatus = "Phone sent a bad reply."
                    connection.cancel()
                    return
                }
                let type = obj["type"] as? String ?? ""
                guard type == "authenticated" else {
                    let reason = obj["reason"] as? String ?? type
                    self.transferStatus = "Phone refused: \(reason)"
                    connection.cancel()
                    return
                }
                self.sendBody(data: data, on: connection)
            }
        }
    }

    /// Send the header line (name, size, hash) then the bytes themselves.
    private func sendBody(data: Data, on connection: NWConnection) {
        struct FileHeaderSend: Encodable {
            let type = "file"
            let filename: String
            let size: Int
            let sha256: String
        }
        let sha = sendingSHA ?? Self.sha256Hex(data)
        let header = FileHeaderSend(
            filename: sendingName ?? "file",
            size: data.count,
            sha256: sha
        )
        guard let line = Self.encodeLine(header) else {
            transferStatus = "Couldn't build the file header."
            connection.cancel()
            return
        }
        connection.send(content: Data((line + "\n").utf8),
                        completion: .contentProcessed { [weak self] error in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let error {
                    self.transferStatus = "Send failed: \(error.localizedDescription)"
                    connection.cancel()
                    return
                }
                self.sendChunks(data, from: 0, on: connection)
            }
        })
    }

    /// Stream the body in 1 MB chunks. NWConnection doesn't need us to wait for
    /// an ack, but sending one chunk at a time keeps memory flat and lets an
    /// error stop the loop cleanly.
    private func sendChunks(_ data: Data, from offset: Int, on connection: NWConnection) {
        if offset >= data.count {
            readTransferResult(on: connection)
            return
        }
        let end = min(offset + Self.fileChunkBytes, data.count)
        let chunk = data.subdata(in: offset..<end)
        connection.send(content: chunk, completion: .contentProcessed { [weak self] error in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let error {
                    self.transferStatus = "Send failed: \(error.localizedDescription)"
                    connection.cancel()
                    return
                }
                self.sendChunks(data, from: end, on: connection)
            }
        })
    }

    /// `received` = kept on the phone; `failed` = it checked the hash and deleted it.
    private func readTransferResult(on connection: NWConnection) {
        readLine(on: connection) { [weak self] line in
            MainActor.assumeIsolated {
                self?.isTransferring = false
                defer { connection.cancel() }
                guard let self else { return }
                guard let line, let obj = Self.decodeObject(line) else {
                    self.transferStatus = "Phone sent no result."
                    return
                }
                switch obj["type"] as? String ?? "" {
                case "received":
                    self.transferStatus = "Sent \(self.sendingName ?? "file") ✓"
                    AavijaSound.shared.transferComplete()
                    GestureEyeModel.shared.delivered("Sent \(self.sendingName ?? "file")")
                    // A held file has been delivered; don't keep its bytes in
                    // memory or offer it to a later gesture.
                    if self.stagedFile?.name == self.sendingName {
                        self.clearStagedFile()
                    }
                case "failed":
                    let reason = obj["reason"] as? String ?? "unknown"
                    self.transferStatus = "Phone deleted the file: \(reason)"
                    GestureEyeModel.shared.failure("Phone rejected the file")
                default:
                    self.transferStatus = "Unexpected reply from the phone."
                    GestureEyeModel.shared.failure("Unexpected reply from the phone")
                }
            }
        }
    }

    private static func encodeLine<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func decodeObject(_ line: String) -> [String: Any]? {
        try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any]
    }

    /// Forget the paired phone (so you can pair a different one).
    func unpair() {
        retryTask?.cancel()
        retryTask = nil
        pairingEndpoint = nil
        stopHeartbeat()
        gestureMatcher.reset()
        clearStagedFile()
        GestureEyeModel.shared.hide()
        store.clear()
        isPaired = false
        pairedPhoneName = nil
        pairingStatus = "Unpaired. Tap this Mac on your phone to pair again."
        resumePolling() // unpaired again — go back to listening for a tap
    }

    /// Clean up on quit.
    func stop() {
        retryTask?.cancel()
        retryTask = nil
        pairingEndpoint = nil
        stopHeartbeat()
        stopPolling()
        gestureMatcher.reset()
        clearStagedFile()
        GestureEyeModel.shared.hide()
        connection?.cancel()
        connection = nil
        state = .idle
    }
}

// MARK: - Wire messages (must match the phone's PairingProtocol JSON)

/// A tiny reference box holding the bytes we've read so far.
///
/// `@unchecked Sendable`: we accept the compiler's hand-off here because only
/// ONE receive is ever in flight at a time for a given connection (each call to
/// `receiveMore` waits for the previous one to finish), so the buffer is never
/// actually touched from two threads at once.
private final class LineBox: @unchecked Sendable {
    var buffer = Data()
}

/// The challenge the phone sends first: a random salt.
private struct Challenge: Decodable {
    let type: String
    let salt: String
}

/// Our reply proving we know the PIN.
private struct PairMessage: Encodable {
    let type = "pair"
    let proof: String
    let name: String
}

/// "Did the user tap my name on the phone?" (Phase 5b). Answered before the
/// user has picked anything, so it must stay cheap and prove nothing.
private struct PollMessage: Encodable {
    let type = "poll"
    let name: String
}

/// The phone's answer to a poll: `pair` (show a PIN) or `wait`.
private struct PollResponse: Decodable {
    let type: String
    let action: String?
}

/// The phone's verdict.
private struct PairResponse: Decodable {
    let type: String
    let token: String?
    let reason: String?
    let retryAfterMs: Double?
}

/// Phase 4a: prove we still hold the token the phone minted for us.
private struct AuthMessage: Encodable {
    let type = "auth"
    let token: String
}

/// A liveness check. The phone answers `pong`; nothing changes on either side.
/// This is what lets the panel say "Disconnected" honestly.
private struct PingMessage: Encodable {
    let type = "ping"
    /// Proves this heartbeat comes from the paired Mac. The phone only adds
    /// gesture fields to the pong when this token verifies.
    let token: String?
}

/// Phase 4b: ask the phone to send any queued file to us.
private struct ReceiveRequestMsg: Encodable {
    let type = "receive"
}

/// Phase 4b: the phone replies with a file header when sending to us.
private struct FileHeaderRecv: Decodable {
    let type: String
    let filename: String
    let size: Int
    let sha256: String
}
