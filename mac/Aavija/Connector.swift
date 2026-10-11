import Network
import Security
import SwiftUI

/// Dials out to a phone and sends it a message — over an ENCRYPTED (TLS)
/// connection (Phase 3a).
///
/// In our SWAPPED architecture the phone is the listener (it has the open
/// port), so the Mac is the one that *connects*. This uses NWConnection with
/// TLS, which we've verified works on this machine.
///
/// - NWConnection + NWProtocolTLS: Network.framework's "client socket" with an
///   encrypted tunnel. The phone presents a self-signed certificate, so in
///   3a we accept whatever cert it shows (encryption-only). Phase 3b replaces
///   that blind accept with real fingerprint pinning + PIN pairing.
@MainActor
final class Connector: ObservableObject {

    enum State: Equatable {
        case idle
        case connecting
        case connected
        case failed(String)
    }

    @Published private(set) var state: State = .idle

    private var connection: NWConnection?

    /// Open a TLS connection to `endpoint`, send `message` + newline, close.
    ///
    /// The newline matters: the phone reads one *line* (up to '\n'), so we
    /// must terminate the message or the phone would wait forever.
    func send(_ message: String, to endpoint: NWEndpoint) {
        // One connection at a time for now — drop any previous one.
        connection?.cancel()
        connection = nil
        state = .connecting

        // Build TLS options. The verify block runs when the phone presents its
        // certificate; for Phase 3a we accept it unconditionally so the tunnel
        // comes up. (3b: verify the pinned fingerprint here instead.)
        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { _, _, completion in
                // TEMPORARY (Phase 3a): trust the self-signed cert so we get
                // encryption. Real authentication = PIN + fingerprint (3b).
                completion(true)
            },
            .main
        )

        let connection = NWConnection(
            to: endpoint,
            using: NWParameters(tls: tlsOptions)
        )
        connection.stateUpdateHandler = { [weak self] nwState in
            MainActor.assumeIsolated {
                switch nwState {
                case .ready:
                    self?.state = .connected
                    self?.sendPayload(message, on: connection)
                case .failed(let error):
                    self?.state = .failed(error.localizedDescription)
                    connection.cancel()
                case .waiting(let error):
                    // .waiting = "can't reach it right now" (e.g. phone slept).
                    self?.state = .failed(error.localizedDescription)
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

    /// Write the message bytes (UTF-8) + a trailing newline, then close.
    private func sendPayload(_ message: String, on connection: NWConnection) {
        // + "\n" so the phone's readLine() returns instead of blocking forever.
        let data = Data((message + "\n").utf8)
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            MainActor.assumeIsolated {
                if let error {
                    self?.state = .failed(error.localizedDescription)
                }
                // We only needed to deliver this one message — hang up.
                connection.cancel()
                self?.connection = nil
                self?.state = .idle
            }
        })
    }

    /// Clean up on quit.
    func stop() {
        connection?.cancel()
        connection = nil
        state = .idle
    }
}
