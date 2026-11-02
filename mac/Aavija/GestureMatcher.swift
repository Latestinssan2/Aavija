import Foundation

/// Phase 7: one gesture as the phone reported it.
///
/// `ageMs` is measured on the PHONE's clock, not an absolute timestamp, so the
/// Mac never needs its clock synchronised with the phone. The Mac subtracts the
/// age from its own "now" to estimate when the gesture happened. `id` increases
/// with every phone gesture, so a repeated heartbeat carrying the same event is
/// not mistaken for a second grab.
struct GestureOffer {
    enum Kind: String {
        case grab, release
    }

    let kind: Kind
    let id: Int64
    let ageMs: Double
}

/// Pairs a gesture on this Mac with a gesture from the phone.
///
/// The rule is physical: one side GRABS (picks the file up), the other side
/// RELEASES (puts it down) within ten seconds. The Mac drives every transfer,
/// so only the phone's gestures need to travel over the wire; the Mac's own are
/// already here.
///
///   - phone grabbed, Mac released  → pull the queued file from the phone
///   - Mac grabbed,   phone released → push the held file to the phone
///
/// Each event can be used once, which is what stops a single grab/release pair
/// from firing a second transfer on the next heartbeat.
final class GestureMatcher {
    enum Action {
        case pullFromPhone
        case sendToPhone
    }

    /// How far apart a grab and a release may be and still count as one gesture.
    static let window: Double = 10.0

    private var localGrabAt: Double?
    private var localGrabUsed = false
    private var localReleaseAt: Double?
    private var localReleaseUsed = false

    private var remoteGrab: (id: Int64, at: Double)?
    private var remoteGrabUsed = false
    private var remoteRelease: (id: Int64, at: Double)?
    private var remoteReleaseUsed = false

    /// Forget all state, e.g. when Gesture mode is switched off.
    func reset() {
        localGrabAt = nil
        localGrabUsed = false
        localReleaseAt = nil
        localReleaseUsed = false
        remoteGrab = nil
        remoteGrabUsed = false
        remoteRelease = nil
        remoteReleaseUsed = false
    }

    /// Feed a gesture detected on THIS Mac.
    func onLocal(_ kind: GestureOffer.Kind, at now: Double) -> Action? {
        switch kind {
        case .grab:
            localGrabAt = now
            localGrabUsed = false
            return nil

        case .release:
            localReleaseAt = now
            localReleaseUsed = false
            guard let grab = remoteGrab, !remoteGrabUsed else { return nil }
            // The phone must have grabbed first, and not too long ago.
            let age = now - grab.at
            guard age >= 0, age <= Self.window else { return nil }
            remoteGrabUsed = true
            localReleaseUsed = true
            return .pullFromPhone
        }
    }

    /// Feed a gesture the phone reported on a heartbeat.
    func onRemote(_ offer: GestureOffer, at now: Double) -> Action? {
        let happenedAt = now - offer.ageMs / 1000.0

        switch offer.kind {
        case .grab:
            // The same event arrives on every heartbeat until it expires.
            if remoteGrab?.id == offer.id { return nil }
            remoteGrab = (offer.id, happenedAt)
            remoteGrabUsed = false

            // We may already have released while the phone's grab was in
            // flight to us. If it still fits the window, act now.
            guard let releaseAt = localReleaseAt, !localReleaseUsed else { return nil }
            let age = releaseAt - happenedAt
            guard age >= 0, age <= Self.window else { return nil }
            localReleaseUsed = true
            remoteGrabUsed = true
            return .pullFromPhone

        case .release:
            if remoteRelease?.id == offer.id { return nil }
            remoteRelease = (offer.id, happenedAt)
            remoteReleaseUsed = false

            // We grabbed earlier and the phone has now released: send what we
            // are holding.
            guard let grabAt = localGrabAt, !localGrabUsed else { return nil }
            let age = happenedAt - grabAt
            guard age >= 0, age <= Self.window else { return nil }
            localGrabUsed = true
            remoteReleaseUsed = true
            return .sendToPhone
        }
    }
}
