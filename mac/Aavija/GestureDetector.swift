import Foundation

/// Phase 6: one hand point as Vision reports it — normalized to the camera
/// frame. `z` is unused for the pose decision (too noisy on a webcam).
struct HandPoint {
    let x: Double
    let y: Double
}

/// What the hand looks like right now. UNKNOWN is a real state: half-curled.
enum HandPose {
    case open, fist, unknown
}

/// The gesture state machine — the Mac twin of Android's HandGestureMachine.
///
/// Frames in, GRAB/RELEASE out. A single frame is never trusted: the hand is
/// ambiguous while moving, and acting on a flicker would send a file because
/// someone scratched their nose. So we classify each frame, keep a 5-frame
/// window (~0.5 s at the 10 fps we sample), require 4-of-5 agreement, and only
/// emit on a real transition with a minimum hold time.
///
/// GRAB = OPEN → FIST. RELEASE = FIST → OPEN.
///
/// On the Mac we ALSO treat a fist as a GRAB when it is the first pose the
/// camera settles on. Without that, someone who raises an already-closed fist
/// (or is still holding one when Gesture mode turns on) would have to open
/// their hand first before the capture gesture works. The 5-frame window still
/// has to agree, so this is a deliberate hold, not a single-frame flicker.
final class HandGestureMachine {
    enum EventType {
        case grab, release
    }

    struct Event {
        let type: EventType
        /// Seconds (CACurrentMediaTime). Only differences matter.
        let at: Double
    }

    private let windowSize: Int
    private let minStableFrames: Int
    private let minHoldSeconds: Double
    private let grabOnFirstFist: Bool

    private var recent: [HandPose] = []
    private var pose: HandPose = .unknown
    private var lastEventAt: Double?
    /// Has any pose settled since the last reset? Used to recognise a fist
    /// that is already being held when the camera starts.
    private var sawSettledPose = false

    init(
        windowSize: Int = 5,
        minStableFrames: Int = 4,
        minHoldSeconds: Double = 0.5,
        grabOnFirstFist: Bool = false
    ) {
        self.windowSize = windowSize
        self.minStableFrames = minStableFrames
        self.minHoldSeconds = minHoldSeconds
        self.grabOnFirstFist = grabOnFirstFist
    }

    /// The settled pose (after smoothing), for the UI.
    var currentPose: HandPose { pose }

    /// Forget everything — call when the hand leaves the frame.
    func reset() {
        recent.removeAll()
        pose = .unknown
        lastEventAt = nil
        sawSettledPose = false
    }

    /// Feed one analysed frame. Returns the event it caused, or nil.
    func onFrame(_ points: [HandPoint?], at now: Double) -> Event? {
        recent.append(Self.classify(points))
        if recent.count > windowSize { recent.removeFirst() }

        guard recent.count >= windowSize else { return nil }

        let fists = recent.filter { $0 == .fist }.count
        let opens = recent.filter { $0 == .open }.count
        let decided: HandPose
        if fists >= minStableFrames {
            decided = .fist
        } else if opens >= minStableFrames {
            decided = .open
        } else {
            decided = .unknown
        }

        guard decided != .unknown else { return nil }

        // First settled pose. Normally it is just the starting point, with no
        // event. On the Mac, `grabOnFirstFist` turns an already-closed fist into
        // a GRAB so a screenshot can be taken without opening the hand first.
        if !sawSettledPose {
            sawSettledPose = true
            pose = decided
            guard decided == .fist, grabOnFirstFist else { return nil }
            if let last = lastEventAt, now - last < minHoldSeconds { return nil }
            lastEventAt = now
            return Event(type: .grab, at: now)
        }

        guard decided != pose else { return nil }

        let previous = pose
        pose = decided

        let type: EventType
        switch (previous, decided) {
        case (.open, .fist): type = .grab
        case (.fist, .open): type = .release
        default:
            return nil
        }

        if let last = lastEventAt, now - last < minHoldSeconds { return nil }
        lastEventAt = now
        return Event(type: type, at: now)
    }

    // MARK: - Classification

    /// Index/middle/ring/little: (fingertip index, PIP index) in the 21-point
    /// layout MediaPipe and Vision share.
    private static let fingers: [(tip: Int, pip: Int)] = [(8, 6), (12, 10), (16, 14), (20, 18)]

    /// Ratio below which a fingertip counts as curled.
    private static let extendedRatio = 1.20

    /// Classify one frame. Distances are ratios (tip-to-wrist / pip-to-wrist),
    /// so hand size, camera distance, and mirroring don't matter.
    ///
    ///  - 3–4 extended fingers → OPEN
    ///  - 0–1 extended fingers → FIST
    ///  - exactly 2         → UNKNOWN (half-open)
    static func classify(_ points: [HandPoint?]) -> HandPose {
        guard points.count >= 21, let wrist = points[0] else { return .unknown }

        var extended = 0
        for (tipIndex, pipIndex) in fingers {
            guard let tip = points[tipIndex], let pip = points[pipIndex] else { continue }
            let tipDistance = distance(tip, wrist)
            let pipDistance = distance(pip, wrist)
            guard pipDistance > 0.0001 else { continue }
            if tipDistance > pipDistance * extendedRatio { extended += 1 }
        }

        if extended >= 3 { return .open }
        if extended <= 1 { return .fist }
        return .unknown
    }

    private static func distance(_ a: HandPoint, _ b: HandPoint) -> Double {
        let dx = a.x - b.x
        let dy = a.y - b.y
        return (dx * dx + dy * dy).squareRoot()
    }
}
