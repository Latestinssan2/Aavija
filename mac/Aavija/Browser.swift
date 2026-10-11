import Network
import SwiftUI

/// Discovers Aavija phones on the local Wi-Fi.
///
/// This is the Mac's half of discovery in the SWAPPED architecture: the phone
/// advertises (it's the listener), and the Mac *browses* for it. We use
/// NWBrowser, which we've verified works on this machine (unlike NWListener).
///
/// - NWBrowser: Network.framework's "client" for Bonjour. It listens on the
///   network for anyone announcing a service type and reports each one it
///   finds (and loses). No IP addresses to type — the OS finds them for us.
@MainActor
final class Browser: ObservableObject {

    /// Our Bonjour service type — must match the phone's exactly.
    static let serviceType = "_gestureshare._tcp"

    /// One device the Mac has found on the network.
    struct Device: Identifiable, Equatable {
        let id: String          // unique per service instance (the Bonjour name)
        let name: String        // human-readable, e.g. "SM-A176B"
        let endpoint: NWEndpoint // the full network address — needed to connect
    }

    @Published private(set) var devices: [Device] = []
    @Published private(set) var isSearching: Bool = false

    /// Called every time the phone list changes. Phase 5b uses this to start
    /// asking a phone whether its user tapped us — the phone can't dial us, so
    /// we have to poll, and polling needs an endpoint to poll.
    ///
    /// Set once at app launch, before the panel is ever opened, so it works
    /// even while the menu-bar window is closed.
    var onDevicesChanged: (([Device]) -> Void)?

    private var browser: NWBrowser?

    /// Kick off browsing as soon as the object is created (at app launch),
    /// so the Mac is discoverable-to-phones even while the panel is closed.
    init() {
        start()
    }

    /// Start listening for phones. Safe to call once at launch.
    func start() {
        guard browser == nil else { return } // already running
        isSearching = true

        // Browse for our service type over Bonjour; TCP is just the transport
        // the phone advertised on.
        let browser = NWBrowser(
            for: .bonjour(type: Self.serviceType, domain: nil),
            using: .tcp
        )

        browser.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                switch state {
                case .ready:
                    // Ready = "actively browsing." We don't flip isSearching
                    // off here; we keep it on to mean "searching is alive."
                    break
                case .failed(let error):
                    self?.isSearching = false
                    self?.devices = []
                    NSLog("Aavija browser failed: \(error)")
                case .waiting:
                    // .waiting usually means no network / permission pending.
                    self?.isSearching = false
                default:
                    break
                }
            }
        }

        // resultsChanged fires every time the set of found devices changes —
        // this is where we update the list shown in the panel.
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated {
                self?.apply(results: results)
            }
        }

        browser.start(queue: .main)
        self.browser = browser
    }

    /// Stop browsing (called on quit / teardown).
    func stop() {
        browser?.cancel()
        browser = nil
        isSearching = false
        devices = []
    }

    /// Turn NWBrowser's raw results into our simple [Device] list.
    ///
    /// API shape (this tripped me up, so noted for the record):
    ///   NWBrowser.Result is a *struct* with an `endpoint` + `interface`.
    ///   The endpoint is an *enum*; for Bonjour it's the `.service` case,
    ///   whose associated `name` is the instance name (e.g. "SM-A176B").
    private func apply(results: Set<NWBrowser.Result>) {
        var found: [Device] = []
        for result in results {
            if case .service(let name, _, _, _) = result.endpoint {
                // Keep the whole endpoint so we can dial this exact phone later.
                found.append(Device(id: name, name: name, endpoint: result.endpoint))
            }
        }
        // Sort by name so the list doesn't jump around as results change.
        let sorted = found.sorted { $0.name < $1.name }
        // Only announce real changes: NWBrowser replays its whole result set
        // often, and waking the poller on every replay would be pointless work.
        guard sorted != devices else { return }
        devices = sorted
        onDevicesChanged?(sorted)
    }
}
