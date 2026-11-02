import AppKit
import SwiftUI

/// A click-through window that plays a brief "liquid glass" capture effect over
/// the whole screen whenever Aavija takes a screenshot.
///
/// It never takes focus and never eats a click (`ignoresMouseEvents`), so it
/// cannot interfere with whatever the user is doing. The window fades itself
/// out after a couple of seconds.
@MainActor
final class CaptureEffectPanel {
    static let shared = CaptureEffectPanel()

    private var panel: NSPanel?

    func show(caption: String) {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }

        // If a previous effect is still on screen, replace it rather than
        // stacking panels.
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
        panel.level = .statusBar
        panel.ignoresMouseEvents = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        panel.contentView = NSHostingView(
            rootView: LiquidGlassCaptureView(caption: caption)
        )
        panel.setFrame(screen.frame, display: true)
        panel.orderFrontRegardless()
        self.panel = panel

        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_100_000_000)
            guard let self, self.panel === panel else { return }
            NSAnimationContext.beginGrouping()
            NSAnimationContext.current.duration = 0.45
            panel.animator().alphaValue = 0
            NSAnimationContext.endGrouping()
            try? await Task.sleep(nanoseconds: 500_000_000)
            panel.orderOut(nil)
            if self.panel === panel { self.panel = nil }
        }
    }
}

/// The actual Liquid Glass visual: slow aurora blobs, a rotating glass rim,
/// drifting particles and a specular sweep, all behind one frosted card.
///
/// On macOS 26+ the card uses the real `glassEffect`; older systems fall back to
/// materials, so the effect still reads as glass rather than a plain box.
struct LiquidGlassCaptureView: View {
    let caption: String

    @State private var appeared = false

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate

            ZStack {
                Color.black
                    .opacity(appeared ? 0.18 : 0)
                    .ignoresSafeArea()

                aurora(t)
                    .opacity(appeared ? 1 : 0)

                card(t)
                    .scaleEffect(appeared ? 1 : 0.55)
                    .offset(y: appeared ? 0 : 18)
                    .opacity(appeared ? 1 : 0)
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.spring(response: 0.52, dampingFraction: 0.70)) {
                appeared = true
            }
        }
    }

    /// Soft coloured blobs that flow around like liquid in the background.
    private func aurora(_ t: TimeInterval) -> some View {
        Canvas { context, size in
            let blobs: [(Color, Double, Double, Double, Double)] = [
                (Color(red: 0.16, green: 0.74, blue: 1.00), 0.22, 0.30, 0.30, 0.37),
                (Color(red: 0.55, green: 0.30, blue: 1.00), 0.76, 0.24, 0.34, 0.31),
                (Color(red: 0.10, green: 0.90, blue: 0.80), 0.55, 0.78, 0.28, 0.41),
                (Color(red: 0.95, green: 0.35, blue: 0.75), 0.18, 0.75, 0.22, 0.53),
            ]

            for (index, blob) in blobs.enumerated() {
                let (color, x, y, radius, speed) = blob
                let wobbleX = sin(t * speed + Double(index) * 1.7) * 0.07
                let wobbleY = cos(t * speed * 0.8 + Double(index) * 2.1) * 0.06
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
                        Gradient(colors: [color.opacity(0.55), color.opacity(0)]),
                        center: center,
                        startRadius: 0,
                        endRadius: r
                    )
                )
            }
        }
        .blur(radius: 64)
        .opacity(0.9)
    }

    private func card(_ t: TimeInterval) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(.ultraThinMaterial)

                Circle()
                    .stroke(
                        AngularGradient(
                            colors: [
                                Color.white.opacity(0.95),
                                Color(red: 0.20, green: 0.85, blue: 1.00).opacity(0.6),
                                Color.white.opacity(0.12),
                                Color(red: 0.60, green: 0.35, blue: 1.00).opacity(0.7),
                                Color.white.opacity(0.95),
                            ],
                            center: .center
                        ),
                        lineWidth: 3
                    )
                    .rotationEffect(.degrees(t * 55))

                Image(systemName: "camera.aperture")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.45), radius: 6, y: 2)
            }
            .frame(width: 78, height: 78)

            Text(caption)
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.45), radius: 4, y: 1)

            Text("Aavija")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.62))
        }
        .padding(.horizontal, 30)
        .padding(.vertical, 24)
        .liquidGlassCard()
        .overlay {
            // A slow specular band crossing the glass.
            GeometryReader { geo in
                let x = geo.size.width * (CGFloat(sin(t * 0.9)) * 0.55 + 0.5)
                LinearGradient(
                    colors: [
                        .clear,
                        .white.opacity(0.22),
                        .white.opacity(0.55),
                        .white.opacity(0.22),
                        .clear,
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                .frame(width: 120)
                .offset(x: x - 60)
                .blur(radius: 6)
                .blendMode(.plusLighter)
            }
            .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
            .allowsHitTesting(false)
        }
    }
}

private extension View {
    @ViewBuilder
    func liquidGlassCard() -> some View {
        if #available(macOS 26.0, *) {
            self
                .glassEffect(
                    .regular,
                    in: RoundedRectangle(cornerRadius: 30, style: .continuous)
                )
                .shadow(color: .black.opacity(0.35), radius: 34, y: 14)
        } else {
            self
                .background(
                    .ultraThinMaterial,
                    in: RoundedRectangle(cornerRadius: 30, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .stroke(
                            LinearGradient(
                                colors: [
                                    .white.opacity(0.70),
                                    .white.opacity(0.10),
                                    .white.opacity(0.35),
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 1
                        )
                }
                .shadow(color: .black.opacity(0.35), radius: 34, y: 14)
        }
    }
}
