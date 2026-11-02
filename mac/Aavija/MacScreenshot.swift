import AppKit
import CoreGraphics
import ScreenCaptureKit

/// Which screenshot target the user wants to capture.
enum ScreenshotMode: String, CaseIterable, Identifiable {
    case entireScreen = "Entire Screen"
    case activeApp = "Active App"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .entireScreen: return "display"
        case .activeApp: return "macwindow"
        }
    }
}

/// Takes a screenshot on the Mac: either entire screen or the active application window.
///
/// We use ScreenCaptureKit rather than older CGDisplay APIs because it is the
/// modern, supported Apple API that properly hooks macOS Screen Recording permissions.
///
/// Nothing is written to disk here: the PNG lives in memory only long enough to
/// become a [Connector.StagedFile], so a capture never leaves a stray file behind.
enum MacScreenshot {

    enum CaptureError: LocalizedError {
        case permission
        case noDisplay
        case noWindow
        case encodeFailed

        var errorDescription: String? {
            switch self {
            case .permission:
                return "Screen Recording is off. Allow Aavija in System Settings → Privacy & Security → Screen & System Audio Recording, then try again (you may need to relaunch Aavija)."
            case .noDisplay:
                return "No display is available to capture. If macOS just asked for Screen Recording permission, allow it and grab again."
            case .noWindow:
                return "No active application window was found to capture."
            case .encodeFailed:
                return "The screenshot couldn't be encoded."
            }
        }
    }

    /// Result of a capture containing raw PNG bytes and suggested filename.
    struct CaptureResult {
        let data: Data
        let filename: String
    }

    /// Capture either the entire screen or the frontmost active application window.
    static func capture(mode: ScreenshotMode = .entireScreen) async throws -> CaptureResult {
        // Request permission if not yet determined/requested, then preflight
        if !ScreenRecordingPermission.isGranted {
            _ = await ScreenRecordingPermission.shared.requestIfNeeded()
        }
        guard ScreenRecordingPermission.isGranted else {
            throw CaptureError.permission
        }

        let content: SCShareableContent
        do {
            content = try await SCShareableContent.excludingDesktopWindows(
                false,
                onScreenWindowsOnly: true
            )
        } catch {
            throw CaptureError.permission
        }

        switch mode {
        case .entireScreen:
            return try await captureDisplay(content: content)
        case .activeApp:
            return try await captureActiveWindow(content: content)
        }
    }

    /// Backward-compatible capture returning raw Data.
    static func capture() async throws -> Data {
        let result = try await capture(mode: .entireScreen)
        return result.data
    }

    // MARK: - Entire Screen Capture

    private static func captureDisplay(content: SCShareableContent) async throws -> CaptureResult {
        let mainID = CGMainDisplayID()
        guard let display = content.displays.first(where: { $0.displayID == mainID })
                ?? content.displays.first
        else {
            throw CaptureError.noDisplay
        }

        let filter = SCContentFilter(
            display: display,
            excludingApplications: [],
            exceptingWindows: []
        )

        let config = SCStreamConfiguration()
        config.width = display.width
        config.height = display.height
        config.showsCursor = false

        let image = try await SCScreenshotManager.captureImage(
            contentFilter: filter,
            configuration: config
        )

        let rep = NSBitmapImageRep(cgImage: image)
        guard let data = rep.representation(using: .png, properties: [:]) else {
            throw CaptureError.encodeFailed
        }

        return CaptureResult(data: data, filename: filename(prefix: "Aavija-Screen"))
    }

    // MARK: - Active App Capture

    private static func captureActiveWindow(content: SCShareableContent) async throws -> CaptureResult {
        let myBundleId = Bundle.main.bundleIdentifier
        let frontmost = NSWorkspace.shared.frontmostApplication

        let ignoredBundlePrefixes = [
            "com.apple.dock",
            "com.apple.controlcenter",
            "com.apple.notificationcenterui",
            "com.apple.WindowManager",
            "com.apple.systemuiserver",
            "com.apple.Spotlight"
        ]

        // Find candidate window belonging to frontmost app
        var targetWindow: SCWindow? = nil

        if let front = frontmost, front.bundleIdentifier != myBundleId {
            targetWindow = content.windows.first { w in
                w.windowLayer == 0 &&
                w.frame.width > 80 &&
                w.frame.height > 80 &&
                w.owningApplication?.processID == front.processIdentifier
            }
        }

        // If frontmost app didn't have a matching layer-0 window (or front was Aavija itself),
        // pick the topmost normal user window in z-order that is not a system chrome
        if targetWindow == nil {
            targetWindow = content.windows.first { w in
                guard w.windowLayer == 0,
                      w.frame.width > 80,
                      w.frame.height > 80,
                      let bundle = w.owningApplication?.bundleIdentifier
                else { return false }

                if bundle == myBundleId { return false }
                if ignoredBundlePrefixes.contains(where: { bundle.hasPrefix($0) }) { return false }
                return true
            }
        }

        // If still no window, fallback to display capture
        guard let window = targetWindow else {
            return try await captureDisplay(content: content)
        }

        let filter = SCContentFilter(desktopIndependentWindow: window)
        let scale = NSScreen.main?.backingScaleFactor ?? 2.0
        let config = SCStreamConfiguration()
        config.width = max(1, Int(window.frame.width * scale))
        config.height = max(1, Int(window.frame.height * scale))
        config.showsCursor = false

        let image = try await SCScreenshotManager.captureImage(
            contentFilter: filter,
            configuration: config
        )

        let rep = NSBitmapImageRep(cgImage: image)
        guard let data = rep.representation(using: .png, properties: [:]) else {
            throw CaptureError.encodeFailed
        }

        // Clean app name for filename
        let rawAppName = window.owningApplication?.applicationName ?? "ActiveApp"
        let sanitizedApp = rawAppName
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .joined(separator: "-")

        let prefix = sanitizedApp.isEmpty ? "Aavija-ActiveApp" : "Aavija-\(sanitizedApp)"
        return CaptureResult(data: data, filename: filename(prefix: prefix))
    }

    /// A filename that is safe on Android too (no spaces, colons, or slashes).
    static func filename(prefix: String = "Aavija-Screenshot", at date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd-HHmmss-SSS"
        return "\(prefix)-\(formatter.string(from: date)).png"
    }
}
