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

    /// Copy the file we're sending so later steps can name it in the result.
    private var sendingName: String?

    /// Have we successfully paired with some phone?
    @Published private(set) var isPaired: Bool

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
            return
        }
        resumePolling()
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

    init(store: PairingStore = PairingStore()) {
        self.store = store
        self.isPaired = store.isPaired
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
        // We're pairing with THIS phone now, so stop asking it questions.
        pausePolling()
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
                    self.isPaired = true
                    self.pairingStatus = "Paired ✓ This Mac now trusts this phone."
                    self.pin = nil
                    self.pairingEndpoint = nil
                    self.retryTask?.cancel()
                    self.retryTask = nil
                    // Stop ASKING the phone whether it wants us — but KEEP the
                    // endpoint we found, because a later file send dials the
                    // very same phone.
                    self.pausePolling()

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
    func sendFile(at url: URL) {
        guard pairingEndpoint == nil else {
            transferStatus = "Finish pairing first."
            return
        }
        guard let endpoint = pollEndpoint else {
            transferStatus = "No phone found on the Wi-Fi yet."
            return
        }
        guard let token = store.sessionToken else {
            transferStatus = "Pair with your phone before sending."
            return
        }

        // Read it up front so we can hash it and fail clearly on a bad path.
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            transferStatus = "Couldn't read that file: \(error.localizedDescription)"
            return
        }
        guard data.count <= Self.maxFileBytes else {
            transferStatus = "File too large (max 200 MB)."
            return
        }

        sendingName = url.lastPathComponent
        transferStatus = "Sending \(url.lastPathComponent)…"
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
                    connection.cancel()
                case .waiting(let error):
                    self?.state = .failed(error.localizedDescription)
                    self?.transferStatus = "Can't reach the phone: \(error.localizedDescription)"
                    connection.cancel()
                case .cancelled:
                    self?.state = .idle
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
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let header = FileHeader(
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
                defer { connection.cancel() }
                guard let self else { return }
                guard let line, let obj = Self.decodeObject(line) else {
                    self.transferStatus = "Phone sent no result."
                    return
                }
                switch obj["type"] as? String ?? "" {
                case "received":
                    self.transferStatus = "Sent \(self.sendingName ?? "file") ✓"
                case "failed":
                    let reason = obj["reason"] as? String ?? "unknown"
                    self.transferStatus = "Phone deleted the file: \(reason)"
                default:
                    self.transferStatus = "Unexpected reply from the phone."
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
        store.clear()
        isPaired = false
        pairingStatus = "Unpaired. Tap this Mac on your phone to pair again."
        resumePolling() // unpaired again — go back to listening for a tap
    }

    /// Clean up on quit.
    func stop() {
        retryTask?.cancel()
        retryTask = nil
        pairingEndpoint = nil
        stopPolling()
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

/// Phase 4a: the file's name, byte count, and SHA-256 (hex), sent as one line
/// immediately before the raw bytes.
private struct FileHeader: Encodable {
    let type = "file"
    let filename: String
    let size: Int
    let sha256: String
}
