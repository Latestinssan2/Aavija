import Foundation
import Network
import Security
import SwiftUI

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

    init(store: PairingStore = PairingStore()) {
        self.store = store
        self.isPaired = store.isPaired
    }

    /// Begin pairing with `endpoint`: generate a PIN, connect, run the handshake.
    func pair(with endpoint: NWEndpoint) {
        connection?.cancel()
        connection = nil
        capturedFingerprint = nil

        // Fresh PIN for this attempt. Shown to the user, typed into the phone.
        let pin = Pairing.generatePin()
        self.pin = pin
        self.pairingStatus = "Waiting for you to type the PIN on your phone…"
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
                    self?.state = .failed(error.localizedDescription)
                    self?.pin = nil
                    connection.cancel()
                case .waiting(let error):
                    // .waiting = "can't reach it right now" (e.g. phone slept).
                    self?.state = .failed(error.localizedDescription)
                    self?.pin = nil
                case .cancelled:
                    if self?.connection === connection {
                        self?.connection = nil
                        self?.state = .idle
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
                    name: Host.current().localizedName ?? "Aavija Mac"
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

                case "locked":
                    self.pairingStatus = "Too many wrong PINs — try again in a few minutes."
                    self.pin = nil

                default: // "rejected"
                    let reason = verdict.reason ?? "wrong pin"
                    self.pairingStatus = "Rejected: \(reason)"
                    self.pin = nil
                }
                connection.cancel()
            }
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

    /// Forget the paired phone (so you can pair a different one).
    func unpair() {
        store.clear()
        isPaired = false
        pairingStatus = "Unpaired. Pair again with the PIN."
    }

    /// Clean up on quit.
    func stop() {
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

/// The phone's verdict.
private struct PairResponse: Decodable {
    let type: String
    let token: String?
    let reason: String?
    let retryAfterMs: Double?
}
