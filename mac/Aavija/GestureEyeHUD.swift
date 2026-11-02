import AppKit
import SwiftUI

// MARK: - Phase

/// What the notch eye is doing right now.
///
/// The eye is deliberately tiny and separate from the menu-bar panel: it is an
/// ambient indicator, not a window. GRAB closes it, RELEASE opens it, and a
/// capture or transfer briefly gives it something to say.
enum GestureEyePhase: Equatable {
    case hidden
    case open
    case closed
    case capturing
    case sharing
    case success
    case failure
}

// MARK: - Model

/// The single source of truth for the eye.
///
/// The connector calls these methods at the same moments it already handles
/// gestures and transfers, so the animation can never disagree with the actual
/// state machine. Sounds live here too: every close is one soft "lid" sound,
/// every open one bright "wake" sound.
@MainActor
final class GestureEyeModel: ObservableObject {
    static let shared = GestureEyeModel()

    @Published private(set) var phase: GestureEyePhase = .hidden
    @Published private(set) var detail = ""

    /// Transfers are allowed to update the HUD only when it is already on
    /// screen. A manual "Send file" outside gesture mode should not make an eye
    /// pop out of the notch.
    var isVisible: Bool { phase != .hidden }

    private var hideTask: Task<Void, Never>?

    /// A hand closed. The eye shuts, with sound.
    func grab() {
        show(.closed, detail: "GRAB detected")
        AavijaSound.shared.eyesClose()
    }

    /// A hand opened. The eye wakes, with sound, then fades if nothing follows.
    func release() {
        show(.open, detail: "RELEASE received")
        AavijaSound.shared.eyesOpen()
        scheduleHide(after: 2.4)
    }

    /// A screenshot is being taken.
    func capturing() {
        show(.capturing, detail: "Taking a screenshot")
    }

    /// A capture finished while the hand is still closed. No second sound:
    /// the grab already made the close noise.
    func holding(_ detail: String) {
        show(.closed, detail: detail)
    }

    /// A capture finished without a matching grab (the manual button). The eye
    /// opens so the result still feels like an "unveil".
    func ready(_ detail: String) {
        show(.open, detail: detail)
        scheduleHide(after: 2.2)
    }

    /// Bytes are moving. Only shown if the eye is already awake.
    func sharing(_ detail: String) {
        guard isVisible else { return }
        show(.sharing, detail: detail)
    }

    /// A transfer finished successfully.
    func delivered(_ detail: String) {
        guard isVisible else { return }
        show(.success, detail: detail)
        scheduleHide(after: 2.5)
    }

    /// Something went wrong. Only shown if the eye is already awake.
    func failure(_ detail: String) {
        guard isVisible else { return }
        show(.failure, detail: detail)
        scheduleHide(after: 3.4)
    }

    /// Put the eye away immediately (gesture mode off, unpair, quit).
    func hide() {
        hideTask?.cancel()
        hideTask = nil
        phase = .hidden
        detail = ""
        GestureEyePanel.shared.dismiss()
    }

    private func show(_ phase: GestureEyePhase, detail: String) {
        hideTask?.cancel()
        hideTask = nil
        GestureEyePanel.shared.show()
        self.detail = detail
        self.phase = phase
    }

    private func scheduleHide(after seconds: Double) {
        hideTask?.cancel()
        hideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.hide()
        }
    }
}

// MARK: - Panel

/// A click-through, non-activating window that places the eye just under the
/// notch / menu bar. It never takes focus, so a glance at the animation cannot
/// steal a keystroke from whatever the user is doing.
@MainActor
final class GestureEyePanel {
    static let shared = GestureEyePanel()

    private static let panelSize = NSSize(width: 280, height: 100)
    private var panel: NSPanel?
    private var dismissTask: Task<Void, Never>?

    func show() {
        dismissTask?.cancel()
        dismissTask = nil

        let panel = self.panel ?? makePanel()
        position(panel)
        panel.alphaValue = 1
        panel.orderFrontRegardless()
    }

    func dismiss() {
        dismissTask?.cancel()
        guard let panel = self.panel else { return }
        dismissTask = Task { @MainActor [weak self] in
            // Let SwiftUI finish the eye's closing/opening transition first.
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }

            NSAnimationContext.beginGrouping()
            NSAnimationContext.current.duration = 0.22
            panel.animator().alphaValue = 0
            NSAnimationContext.endGrouping()

            try? await Task.sleep(nanoseconds: 240_000_000)
            guard !Task.isCancelled else { return }
            panel.orderOut(nil)
            panel.alphaValue = 1
            self?.dismissTask = nil
        }
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(origin: .zero, size: Self.panelSize),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.ignoresMouseEvents = true
        panel.isMovable = false
        panel.collectionBehavior = [
            .canJoinAllSpaces,
            .fullScreenAuxiliary,
            .stationary,
            .ignoresCycle,
        ]
        panel.contentView = NSHostingView(
            rootView: GestureEyeHUDView(model: .shared)
        )
        self.panel = panel
        return panel
    }

    /// Sit below the menu bar / notch on the display the pointer is on.
    private func position(_ panel: NSPanel) {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first {
            NSMouseInRect(mouse, $0.frame, false)
        } ?? NSScreen.main ?? NSScreen.screens.first
        guard let screen else { return }

        let size = Self.panelSize
        // visibleFrame already ends below the menu bar. On a notched Mac the
        // safe-area inset gives the same line; take the lower of the two so the
        // pod never covers the menu bar.
        let menuBarBottom = screen.visibleFrame.maxY
        let notchBottom = screen.frame.maxY - screen.safeAreaInsets.top
        let top = min(menuBarBottom, notchBottom)

        let origin = NSPoint(
            x: screen.frame.midX - size.width / 2,
            y: top - size.height - 8
        )
        panel.setFrame(NSRect(origin: origin, size: size), display: true)
    }
}

// MARK: - HUD

/// The little glass pod: an eye on the left, a short status on the right.
struct GestureEyeHUDView: View {
    @ObservedObject var model: GestureEyeModel

    var body: some View {
        ZStack {
            if model.phase != .hidden {
                card
                    .transition(
                        .scale(scale: 0.86, anchor: .top)
                        .combined(with: .opacity)
                    )
            }
        }
        .frame(width: 280, height: 100)
        .animation(
            .spring(response: 0.38, dampingFraction: 0.78),
            value: model.phase
        )
    }

    private var card: some View {
        ZStack {
            // A dark tint inside the glass keeps the white type legible over
            // bright desktops. It sits behind the content, above the blur.
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Color.black.opacity(0.38))

            HStack(spacing: 12) {
                TimelineView(
                    .animation(
                        minimumInterval: 1.0 / 30.0,
                        paused: !isAnimated
                    )
                ) { timeline in
                    GestureEyeView(
                        phase: model.phase,
                        now: timeline.date.timeIntervalSinceReferenceDate
                    )
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(accent)
                            .frame(width: 5, height: 5)
                            .shadow(color: accent.opacity(0.9), radius: 3)
                        if model.phase == .sharing {
                            Text("જતું છે")
                                .font(.system(size: 15, weight: .black, design: .rounded))
                                .foregroundStyle(
                                    LinearGradient(
                                        colors: [
                                            Color(red: 0.20, green: 0.90, blue: 1.00),
                                            Color.white,
                                            Color(red: 0.70, green: 0.40, blue: 1.00),
                                        ],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .shadow(color: Color(red: 0.20, green: 0.90, blue: 1.00).opacity(0.85), radius: 6)
                        } else {
                            Text(title)
                                .font(.system(size: 12.5, weight: .semibold, design: .rounded))
                                .foregroundStyle(.white.opacity(0.96))
                        }
                    }

                    Text(model.detail)
                        .font(.system(size: 10.5, weight: .medium, design: .rounded))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .minimumScaleFactor(0.85)
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
        }
        .frame(width: 260, height: 80)
        .eyeGlassCard()
        .overlay {
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [
                            .white.opacity(0.52),
                            .white.opacity(0.07),
                            .white.opacity(0.24),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        }
        .shadow(color: .black.opacity(0.42), radius: 20, y: 10)
    }

    /// Text and iris motion only matter in these phases. When the eye is closed
    /// or hidden, the 30 fps timeline is paused to keep the app quiet.
    private var isAnimated: Bool {
        switch model.phase {
        case .capturing, .sharing, .success, .failure, .open:
            return true
        case .hidden, .closed:
            return false
        }
    }

    private var title: String {
        switch model.phase {
        case .hidden: return ""
        case .open: return "Eyes open"
        case .closed: return "Holding"
        case .capturing: return "Capturing"
        case .sharing: return "જતું છે"
        case .success: return "Delivered"
        case .failure: return "Needs attention"
        }
    }

    private var accent: Color {
        switch model.phase {
        case .open:
            return Color(red: 0.30, green: 0.84, blue: 1.00)
        case .closed:
            return Color(red: 0.38, green: 0.60, blue: 1.00)
        case .capturing:
            return Color(red: 0.18, green: 0.96, blue: 0.88)
        case .sharing:
            return Color(red: 0.63, green: 0.47, blue: 1.00)
        case .success:
            return Color(red: 0.32, green: 0.95, blue: 0.61)
        case .failure:
            return Color(red: 1.00, green: 0.56, blue: 0.30)
        case .hidden:
            return .clear
        }
    }
}

// MARK: - Eye

/// One drawn eye. The almond itself is a Shape whose `openness` is animatable,
/// so closing is a real squash of the eye — not a cross-fade between two
/// pictures. Everything inside is clipped to that almond, which is why the iris
/// disappears naturally when the lids meet.
private struct GestureEyeView: View {
    let phase: GestureEyePhase
    let now: TimeInterval

    /// A natural blink, only while the eye is supposed to be open.
    @State private var blink: Double = 1.0

    private var openness: Double {
        switch phase {
        case .hidden: return 0.0
        case .open: return 1.0
        case .closed: return 0.055
        case .capturing: return 0.52
        case .sharing: return 0.84
        case .success: return 1.0
        case .failure: return 0.42
        }
    }

    private var effectiveOpenness: Double {
        max(0.0, min(1.0, openness * blink))
    }

    private var accent: Color {
        switch phase {
        case .open: return Color(red: 0.28, green: 0.84, blue: 1.00)
        case .closed: return Color(red: 0.38, green: 0.60, blue: 1.00)
        case .capturing: return Color(red: 0.18, green: 0.96, blue: 0.88)
        case .sharing: return Color(red: 0.63, green: 0.47, blue: 1.00)
        case .success: return Color(red: 0.32, green: 0.95, blue: 0.61)
        case .failure: return Color(red: 1.00, green: 0.56, blue: 0.30)
        case .hidden: return .clear
        }
    }

    private var gazeX: CGFloat {
        phase == .capturing
            ? CGFloat(sin(now * 3.1)) * 3.0
            : CGFloat(sin(now * 0.85)) * 2.2
    }

    private var gazeY: CGFloat {
        phase == .capturing
            ? CGFloat(cos(now * 2.6)) * 1.3
            : CGFloat(cos(now * 1.15)) * 1.1
    }

    var body: some View {
        ZStack {
            // Ambient glow behind the almond.
            GestureEyeShape(openness: max(effectiveOpenness, 0.08))
                .fill(accent.opacity(0.22))
                .blur(radius: 8)

            // Sclera + iris, clipped to the almond.
            GestureEyeShape(openness: effectiveOpenness)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(red: 0.99, green: 1.00, blue: 1.00),
                            Color(red: 0.80, green: 0.91, blue: 0.99),
                            Color(red: 0.96, green: 0.99, blue: 1.00),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .overlay { iris }
                .clipShape(GestureEyeShape(openness: effectiveOpenness))

            // The lid line: a thin warm-cool rim that stays visible even when
            // the eye is shut, so "closed" reads as a closed eye, not a blank.
            GestureEyeShape(openness: max(effectiveOpenness, 0.035))
                .stroke(
                    LinearGradient(
                        colors: [
                            accent.opacity(0.95),
                            .white.opacity(0.55),
                            accent.opacity(0.80),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    ),
                    lineWidth: 1.3
                )

            if phase == .capturing {
                scanLine
            }

            if phase == .success {
                checkBadge
            }
        }
        .frame(width: 66, height: 42)
        .animation(
            .spring(response: 0.42, dampingFraction: 0.72),
            value: phase
        )
        .task(id: phase) {
            blink = 1
            guard phase == .open || phase == .success else { return }
            while !Task.isCancelled {
                try? await Task.sleep(
                    for: .seconds(Double.random(in: 2.7...4.5))
                )
                guard !Task.isCancelled else { return }
                withAnimation(.easeIn(duration: 0.09)) { blink = 0.04 }
                try? await Task.sleep(for: .milliseconds(115))
                guard !Task.isCancelled else { return }
                withAnimation(.easeOut(duration: 0.17)) { blink = 1 }
            }
        }
        .accessibilityHidden(true)
    }

    private var iris: some View {
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [
                            accent.opacity(0.97),
                            accent.opacity(0.58),
                            Color(red: 0.04, green: 0.09, blue: 0.18),
                        ],
                        center: .center,
                        startRadius: 1,
                        endRadius: 12
                    )
                )
                .frame(width: 23, height: 23)
                .overlay {
                    Circle().stroke(.white.opacity(0.34), lineWidth: 0.9)
                }

            Circle()
                .fill(Color.black.opacity(0.92))
                .frame(width: 9, height: 9)

            Circle()
                .fill(.white.opacity(0.92))
                .frame(width: 3.2, height: 3.2)
                .offset(x: -3.6, y: -3.6)
                .blur(radius: 0.25)

            Circle()
                .fill(.white.opacity(0.48))
                .frame(width: 1.5, height: 1.5)
                .offset(x: 3.2, y: 2.2)

            if phase == .sharing {
                Circle()
                    .trim(from: 0, to: 0.42)
                    .stroke(
                        .white.opacity(0.88),
                        style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
                    )
                    .frame(width: 29, height: 29)
                    .rotationEffect(.degrees(now * 210))
                    .blendMode(.plusLighter)
            }
        }
        .offset(x: gazeX, y: gazeY)
    }

    private var scanLine: some View {
        GeometryReader { geo in
            let progress = (sin(now * 4.2) + 1) / 2
            let y = 4 + progress * max(0, geo.size.height - 8)
            Rectangle()
                .fill(accent.opacity(0.9))
                .frame(width: geo.size.width * 0.82, height: 1.2)
                .position(x: geo.size.width / 2, y: y)
                .blur(radius: 0.6)
                .shadow(color: accent.opacity(0.9), radius: 4)
                .blendMode(.plusLighter)
        }
        .clipShape(GestureEyeShape(openness: effectiveOpenness))
    }

    private var checkBadge: some View {
        ZStack {
            Circle()
                .fill(Color(red: 0.12, green: 0.75, blue: 0.45))
            Image(systemName: "checkmark")
                .font(.system(size: 8, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: 15, height: 15)
        .offset(x: 25, y: 13)
        .shadow(color: .black.opacity(0.30), radius: 3, y: 1)
    }
}

/// The almond. `openness` 1 = wide awake, 0 = a single lash-line.
private struct GestureEyeShape: Shape {
    var openness: Double

    var animatableData: Double {
        get { openness }
        set { openness = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let open = max(0.0, min(1.0, openness))
        let midY = rect.midY
        let half = rect.height * 0.38 * open
        let left = CGPoint(x: rect.minX + rect.width * 0.02, y: midY)
        let right = CGPoint(x: rect.maxX - rect.width * 0.02, y: midY)
        let top = CGPoint(x: rect.midX, y: midY - half * 1.9)
        let bottom = CGPoint(x: rect.midX, y: midY + half * 1.9)

        var path = Path()
        path.move(to: left)
        path.addQuadCurve(to: right, control: top)
        path.addQuadCurve(to: left, control: bottom)
        path.closeSubpath()
        return path
    }
}

// MARK: - Glass

private extension View {
    @ViewBuilder
    func eyeGlassCard() -> some View {
        if #available(macOS 26.0, *) {
            self.glassEffect(
                .regular,
                in: RoundedRectangle(cornerRadius: 26, style: .continuous)
            )
        } else {
            self
                .background(
                    .ultraThinMaterial,
                    in: RoundedRectangle(cornerRadius: 26, style: .continuous)
                )
        }
    }
}
