import AppKit
import CoreGraphics
import ScreenCaptureKit

/// Takes a full-screen screenshot on the Mac.
///
/// We use ScreenCaptureKit rather than the older `CGDisplayCreateImage`
/// because it is the supported API on current macOS and, importantly, it is
/// what makes the system show the "Screen & System Audio Recording" consent
/// prompt. After the user allows it once, screenshots are silent and instant.
///
/// Nothing is written to disk here: the PNG lives in memory only long enough to
/// become a [Connector.StagedFile], so a capture never leaves a stray file
/// behind if the user changes their mind.
enum MacScreenshot {

    enum CaptureError: LocalizedError {
        case permission
        case noDisplay
        case encodeFailed

        var errorDescription: String? {
            switch self {
            case .permission:
                return "Screen Recording is off. Allow Aavija in System Settings → Privacy & Security → Screen & System Audio Recording, then try again."
            case .noDisplay:
                return "No display is available to capture. If macOS just asked for Screen Recording permission, allow it and grab again."
            case .encodeFailed:
                return "The screenshot couldn't be encoded."
            }
        }
    }

    /// Capture the main display as PNG data.
    ///
    /// This is deliberately `nonisolated` and async: the expensive capture and
    /// PNG encoding happen off the main actor, so the menu-bar panel keeps
    /// animating while a 5K screen is compressed.
    static func capture() async throws -> Data {
        let content: SCShareableContent
        do {
            content = try await SCShareableContent.excludingDesktopWindows(
                false,
                onScreenWindowsOnly: true
            )
        } catch {
            // The usual cause is a refused Screen Recording permission.
            throw CaptureError.permission
        }

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
        return data
    }

    /// A filename that is safe on Android too (no spaces, colons, or slashes).
    static func filename(at date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd-HHmmss-SSS"
        return "Aavija-Screenshot-\(formatter.string(from: date)).png"
    }
}
