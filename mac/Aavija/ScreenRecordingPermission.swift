import CoreGraphics
import Foundation

/// Screen-recording consent, handled in one place.
///
/// macOS keys a screen-recording grant to the app's *designated requirement*.
/// An ad-hoc signature normally gets a cdhash-only requirement, which changes
/// on every rebuild — that is why the system kept asking even though Aavija was
/// already ticked in Settings. `Aavija.requirements` pins the requirement to the
/// bundle identifier instead, so one grant survives future builds.
///
/// This wrapper also makes sure we only ever trigger the system prompt once per
/// launch. `CGPreflightScreenCaptureAccess()` is a silent check; only
/// `CGRequestScreenCaptureAccess()` can prompt. A denied app is not re-prompted
/// by macOS (the header is explicit about that), so there is no prompt loop.
actor ScreenRecordingPermission {
    static let shared = ScreenRecordingPermission()

    private var didRequestThisLaunch = false

    /// The current answer, without prompting.
    nonisolated static var isGranted: Bool {
        CGPreflightScreenCaptureAccess()
    }

    /// Ask at most once per launch. Returns whether capture is allowed now.
    func requestIfNeeded() -> Bool {
        if Self.isGranted { return true }
        guard !didRequestThisLaunch else { return false }
        didRequestThisLaunch = true
        return CGRequestScreenCaptureAccess()
    }
}
