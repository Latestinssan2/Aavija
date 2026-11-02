import AppKit
import SwiftUI

/// A click-through window that plays a brief ambient aurora whenever Aavija
/// takes a screenshot.
///
/// This used to be a full-screen card with a title and caption. Now the notch
/// eye is the thing that talks; the screen effect's only job is to make the
/// capture feel physical — a soft colour wash and an edge glint, no text, no
/// centre-stage panel. It never takes focus or eats a click.
@MainActor
final class CaptureEffectPanel {
    static let shared = CaptureEffectPanel()

    private var panel: NSPanel?
    private var dismissTask: Task<Void, Never>?

    func show() {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }

        dismissTask?.cancel()
        panel?.orderOut(nil)

        let panel = NSPanel(
            contentRect: screen.frame,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        // Keep the ambient capture shimmer BELOW the notch eye, which lives at
        // status-bar level, so the eye stays readable while the screen flashes.
        panel.level = .floating
        panel.ignoresMouseEvents = true
        panel.collectionBehavior = [
            .canJoinAllSpaces,
            .fullScreenAuxiliary,
            .stationary,
        ]
        panel.contentView = NSHostingView(rootView: AmbientCaptureView())
        panel.setFrame(screen.frame, display: true)
        panel.alphaValue = 1
        panel.orderFrontRegardless()
        self.panel = panel

        dismissTask = Task { @MainActor [weak self] in
            // Let the aurora bloom, then fade the whole window out.
            try? await Task.sleep(nanoseconds: 850_000_000)
            guard !Task.isCancelled, let self, self.panel === panel else { return }

            NSAnimationContext.beginGrouping()
            NSAnimationContext.current.duration = 0.50
            panel.animator().alphaValue = 0
            NSAnimationContext.endGrouping()

            try? await Task.sleep(nanoseconds: 520_000_000)
            guard !Task.isCancelled else { return }
            panel.orderOut(nil)
            if self.panel === panel { self.panel = nil }
        }
    }
}

/// Soft blobs at the screen edges, a bright edge glint, and one quick white
/// breath. Deliberately understated: at 18 % black it reads as a shimmer, not
/// a modal dialog.
struct AmbientCaptureView: View {
    @State private var appeared = false
    @State private var flash = true

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate

            ZStack {
                aurora(t)
                    .opacity(appeared ? 0.85 : 0)
                    .blur(radius: 72)

                edgeGlint
                    .opacity(appeared ? 0.60 : 0)

                Color.white
                    .opacity(flash ? 0.075 : 0)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.easeOut(duration: 0.32)) {
                appeared = true
            }
            withAnimation(.easeOut(duration: 0.62)) {
                flash = false
            }
        }
    }

    private func aurora(_ t: TimeInterval) -> some View {
        Canvas { context, size in
            // Pushed toward the edges so the desktop stays readable.
            let blobs: [(Color, Double, Double, Double, Double)] = [
                (Color(red: 0.14, green: 0.72, blue: 1.00), 0.12, 0.82, 0.30, 0.34),
                (Color(red: 0.58, green: 0.28, blue: 1.00), 0.88, 0.16, 0.28, 0.28),
                (Color(red: 0.10, green: 0.92, blue: 0.78), 0.80, 0.86, 0.26, 0.40),
                (Color(red: 0.96, green: 0.36, blue: 0.72), 0.22, 0.14, 0.22, 0.50),
            ]

            for (index, blob) in blobs.enumerated() {
                let (color, x, y, radius, speed) = blob
                let wobbleX = sin(t * speed + Double(index) * 1.7) * 0.05
                let wobbleY = cos(t * speed * 0.8 + Double(index) * 2.1) * 0.04
                let center = CGPoint(
                    x: size.width * (x + wobbleX),
                    y: size.height * (y + wobbleY)
                )
                let r = size.width * radius

                context.fill(
                    Path(ellipseIn: CGRect(
                        x: center.x - r,
                        y: center.y - r,
                        width: r * 2,
                        height: r * 2
                    )),
                    with: .radialGradient(
                        Gradient(colors: [color.opacity(0.42), color.opacity(0)]),
                        center: center,
                        startRadius: 0,
                        endRadius: r
                    )
                )
            }
        }
        .opacity(0.7)
    }

    /// A thin light along the very edge of the display, like a shutter blade
    /// catching the light.
    private var edgeGlint: some View {
        Rectangle()
            .strokeBorder(
                LinearGradient(
                    colors: [
                        Color(red: 0.40, green: 0.90, blue: 1.00).opacity(0.55),
                        .white.opacity(0.10),
                        Color(red: 0.62, green: 0.45, blue: 1.00).opacity(0.45),
                        .white.opacity(0.10),
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                lineWidth: 3
            )
            .blur(radius: 5)
    }
}
