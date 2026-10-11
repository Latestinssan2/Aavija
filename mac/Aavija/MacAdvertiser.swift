import Foundation
import dnssd

/// Announces THIS Mac on the Wi-Fi so a phone can list it as an available
/// device (Phase 5a).
///
/// ### Why the phone needs this
/// In our swapped architecture the phone is the listener and the Mac *dials
/// out*. That means a phone has no way to discover a Mac on its own — it never
/// hears about one until the Mac chooses to connect. To show a device list, the
/// Mac has to say "I exist" on the network.
///
/// ### Why this uses DNSServiceRegister and NOT NWListener
/// This is the whole reason Phase 5a was a gamble worth testing first.
/// `NWListener` — the normal way to publish a Bonjour service on macOS — is
/// broken on this machine: it fails with `EINVAL` in every context we tried,
/// because it insists on *binding and listening* for connections.
///
/// `DNSServiceRegister` does something fundamentally different: it asks the
/// system's `mDNSResponder` daemon to broadcast a few UDP packets saying
/// "this name is a service of this type." It opens **no listening socket at
/// all** — so it sidesteps the broken code path entirely.
///
/// Verified on this machine before we built anything on top of it:
/// `DNSServiceRegister -> 0` (kDNSServiceErr_NoError), and a separate
/// `dns-sd -B` browser picked the name up off the wire.
///
/// ### Nobody connects to us
/// The port we advertise is a dummy. The phone only reads the *instance name*
/// (the Mac's real machine name) to build its list. Dialing out to the phone
/// remains the Mac's job, exactly as before.
final class MacAdvertiser {

    /// Service type we publish. Distinct from `_gestureshare._tcp` (which is
    /// the *phone's* file service) because this record describes the Mac.
    static let serviceType = "_aavija-mac._tcp"

    /// The real Mac name we're broadcasting, e.g. "Sandipkumar's MacBook Pro".
    private(set) var machineName: String = Host.current().localizedName ?? "Mac"

    /**
     * THIS machine's display name, with the fallbacks spelled out once.
     *
     * It has to be a single function because three different places must agree
     * byte-for-byte: the name we BROADCAST over mDNS, the name we POLL the
     * phone with, and the name we send when we PAIR. The phone matches that
     * string against the row you tapped — if any one of them fell back
     * differently, the phone would say "not the selected device" and pairing
     * would silently never work.
     */
    static func displayName() -> String {
        Host.current().localizedName
            ?? ProcessInfo.processInfo.hostName
            .replacingOccurrences(of: ".local", with: "")
    }

    /// Handle for the running registration. Must be kept alive for as long as
    /// we want to stay visible — releasing it withdraws us from the network.
    private var serviceRef: DNSServiceRef?

    /// True once mDNSResponder accepted our registration.
    private(set) var isAdvertising = false

    /// Start announcing. Safe to call more than once.
    func start() {
        guard serviceRef == nil else { return }

        // Host.current().localizedName is macOS's "Computer Name" — the same
        // string shown in Finder's sidebar. Fall back to the raw hostname if
        // it's unavailable so we never register an empty instance name.
        let name = Self.displayName()
        machineName = name

        // A dummy port: nobody dials this. mDNS needs *a* port field, and 0
        // can be rejected by some resolvers, so we advertise a closed one.
        let dummyPort: UInt16 = 9 // 9 = "discard" — intentionally nothing.

        var ref: DNSServiceRef?
        let error = DNSServiceRegister(
            &ref,
            0,                  // flags: none (a normal, persistent record)
            0,                  // interfaceIndex: 0 = all interfaces
            name,               // instance name = the real machine name
            Self.serviceType,   // type, e.g. "_aavija-mac._tcp"
            nil,                // domain: nil = default (.local)
            nil,                // host: nil = let the system answer
            dummyPort,          // port (network byte order handled by the API)
            0,                  // txtLen: no TXT record needed
            nil,                // txtRecord
            nil,                // callback: dns_sd.h says "may be NULL"
            nil                 // context for the callback
        )

        guard error == kDNSServiceErr_NoError, let ref = ref else {
            NSLog("Aavija: DNSServiceRegister failed (\(error))")
            isAdvertising = false
            return
        }

        // Hand the reference to a dispatch queue so mDNSResponder can deliver
        // events (including "another Mac took our name" collisions). Without
        // this the registration would sit unprocessed and never go live.
        let queueError = DNSServiceSetDispatchQueue(ref, DispatchQueue.main)
        guard queueError == kDNSServiceErr_NoError else {
            NSLog("Aavija: DNSServiceSetDispatchQueue failed (\(queueError))")
            DNSServiceRefDeallocate(ref)
            isAdvertising = false
            return
        }

        serviceRef = ref
        isAdvertising = true
        NSLog("Aavija: advertising '\(name)' as \(Self.serviceType)")
    }

    /// Stop announcing (called on quit).
    func stop() {
        guard let ref = serviceRef else { return }
        // Per dns_sd.h, a ref scheduled with DNSServiceSetDispatchQueue must be
        // deallocated on that same queue — and we used .main, which is where
        // this runs when called from app lifecycle code.
        DNSServiceRefDeallocate(ref)
        serviceRef = nil
        isAdvertising = false
        NSLog("Aavija: stopped advertising")
    }

    deinit {
        stop()
    }
}
