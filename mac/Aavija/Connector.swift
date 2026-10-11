import Network
import SwiftUI

/// Dials out to a phone and sends it a message.
///
/// In our SWAPPED architecture the phone is the listener (it has the open
/// port), so the Mac is the one that *connects*. This uses NWConnection,
/// which we've verified works on this machine.
///
/// - NWConnection: Network.framework's "client socket". You hand it an
///   endpoint (address + port, which Bonjour gave us) and it opens a TCP
///   connection you can then send bytes over.
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

    /// Open a connection to `endpoint`, send `message` + newline, then close.
    ///
    /// The newline matters: the phone reads one *line* (up to '\n'), so we
    /// must terminate the message or the phone would wait forever.
    func send(_ message: String, to endpoint: NWEndpoint) {
        // One connection at a time for now — drop any previous one.
        connection?.cancel()
        connection = nil
        state = .connecting

        let connection = NWConnection(to: endpoint, using: .tcp)
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
        var data = Data((message + "\n").utf8)
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
