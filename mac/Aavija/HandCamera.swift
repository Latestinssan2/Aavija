import AVFoundation
import SwiftUI
import Vision

/// Phase 6: the Mac's camera → Vision hand landmarks → HandGestureMachine.
///
/// The camera exists ONLY while Gesture mode is ON (default OFF), and nothing
/// is recorded or transmitted: a frame becomes 21 points, the points become
/// GRAB/RELEASE, and the frame is discarded. Vision is sampled at ~10 fps, the
/// rate the 5-frame smoothing window is tuned for.
@MainActor
final class GestureController: ObservableObject {
    @Published private(set) var isEnabled = false
    @Published private(set) var isRunning = false
    @Published private(set) var pose: HandPose = .unknown
    @Published private(set) var lastEventText: String?
    @Published private(set) var errorText: String?

    private let worker = HandCameraWorker()
    private var wired = false

    /// Phase 7: called for every detected GRAB/RELEASE, so the connector can
    /// match it with the phone's gesture and start a transfer.
    var onGesture: ((HandGestureMachine.EventType) -> Void)?

    /// Phase 7: tells the connector whether gesture mode is effectively on, so
    /// it can tighten the heartbeat and reset stale match state.
    var onModeChanged: ((Bool) -> Void)?

    /// The capture session, for the preview layer.
    var session: AVCaptureSession { worker.session }

    var poseHint: String {
        switch pose {
        case .open: return "Open hand — ready to grab"
        case .fist: return "Fist — holding"
        case .unknown: return "Show your hand to the camera"
        }
    }

    /// Flip the switch. Everything else follows from the camera permission.
    func setEnabled(_ on: Bool) {
        if on { start() } else { stop() }
    }

    private func start() {
        guard !isRunning else { return }
        errorText = nil

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            begin()
        case .notDetermined:
            // Show the toggle as ON while the system prompt is up, so the
            // switch doesn't snap back while the user decides.
            isEnabled = true
            onModeChanged?(true)
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    if granted {
                        self?.begin()
                    } else {
                        self?.denied()
                    }
                }
            }
        default:
            denied()
        }
    }

    private func begin() {
        wireCallbacksIfNeeded()
        isEnabled = true
        onModeChanged?(true)
        guard !isRunning else { return }
        isRunning = true
        worker.start()
    }

    private func denied() {
        isEnabled = false
        isRunning = false
        onModeChanged?(false)
        errorText = "Camera access is off. Turn it on in System Settings → Privacy & Security → Camera."
    }

    func stop() {
        isEnabled = false
        isRunning = false
        pose = .unknown
        onModeChanged?(false)
        worker.stop()
    }

    private func wireCallbacksIfNeeded() {
        guard !wired else { return }
        wired = true

        worker.onPose = { [weak self] pose in
            Task { @MainActor in self?.pose = pose }
        }
        worker.onEvent = { [weak self] type in
            Task { @MainActor in
                self?.lastEventText = (type == .grab) ? "GRAB ✊" : "RELEASE 🖐"
                // Phase 7: pass to the connector so it can pair with the
                // phone's gesture and trigger a transfer within 10 seconds.
                self?.onGesture?(type)
            }
        }
        worker.onError = { [weak self] message in
            Task { @MainActor in
                self?.errorText = message
                self?.isEnabled = false
                self?.isRunning = false
                self?.onModeChanged?(false)
            }
        }
    }
}

/// Owns AVFoundation + Vision. Not main-actor-bound: the capture callback and
/// Vision run on their own serial queues, and results hop back explicitly.
private final class HandCameraWorker: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    let session = AVCaptureSession()

    var onPose: (@Sendable (HandPose) -> Void)?
    var onEvent: (@Sendable (HandGestureMachine.EventType) -> Void)?
    var onError: (@Sendable (String) -> Void)?

    private let videoQueue = DispatchQueue(label: "com.preet.aavija.hand-camera")
    private let visionQueue = DispatchQueue(label: "com.preet.aavija.hand-vision")
    private let machine = HandGestureMachine()

    private var isConfigured = false
    private var lastFrameAt: Double = 0
    private var lastHandAt: Double = 0

    func start() {
        videoQueue.async { [weak self] in
            guard let self else { return }
            guard self.configureIfNeeded() else { return }
            if !self.session.isRunning {
                self.session.startRunning()
            }
            NSLog("Aavija: gesture camera started (running=%d)", self.session.isRunning ? 1 : 0)
        }
    }

    func stop() {
        videoQueue.async { [weak self] in
            guard let self else { return }
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.machine.reset()
            NSLog("Aavija: gesture camera stopped")
        }
    }

    private func configureIfNeeded() -> Bool {
        if isConfigured { return true }

        guard let device = AVCaptureDevice.default(for: .video) else {
            onError?("No camera found on this Mac.")
            return false
        }
        do {
            let input = try AVCaptureDeviceInput(device: device)

            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
            output.setSampleBufferDelegate(self, queue: visionQueue)

            session.beginConfiguration()
            session.sessionPreset = .vga640x480
            guard session.canAddInput(input), session.canAddOutput(output) else {
                session.commitConfiguration()
                onError?("This Mac's camera can't be used for gestures.")
                return false
            }
            session.addInput(input)
            session.addOutput(output)
            session.commitConfiguration()
            isConfigured = true
            return true
        } catch {
            onError?("Could not open the camera: \(error.localizedDescription)")
            return false
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        // ~10 fps. Vision at 30 fps would just burn power to fill the same
        // five-frame window faster.
        let now = CACurrentMediaTime()
        if now - lastFrameAt < 0.1 { return }
        lastFrameAt = now

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNDetectHumanHandPoseRequest()
        request.maximumHandCount = 1
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])

        do {
            try handler.perform([request])

            guard let observation = request.results?.first else {
                // A frame or two without a hand is normal. Only forget the pose
                // after a clear absence, so one dropped frame can't fabricate a
                // GRAB/RELEASE transition.
                if lastHandAt > 0, now - lastHandAt > 0.8 {
                    lastHandAt = 0
                    machine.reset()
                    onPose?(.unknown)
                }
                return
            }

            lastHandAt = now
            let recognized = try observation.recognizedPoints(.all)
            let points = Self.points(from: recognized)

            if let event = machine.onFrame(points, at: now) {
                onEvent?(event.type)
            }
            onPose?(machine.currentPose)
        } catch {
            // One failed Vision frame is not worth a UI error; the next frame
            // usually succeeds.
        }
    }

    // MARK: - Landmarks

    /// The order MediaPipe uses, so the classifier is literally the same
    /// geometry on both platforms.
    private static let jointOrder: [VNHumanHandPoseObservation.JointName] = [
        .wrist,
        .thumbCMC, .thumbMP, .thumbIP, .thumbTip,
        .indexMCP, .indexPIP, .indexDIP, .indexTip,
        .middleMCP, .middlePIP, .middleDIP, .middleTip,
        .ringMCP, .ringPIP, .ringDIP, .ringTip,
        .littleMCP, .littlePIP, .littleDIP, .littleTip,
    ]

    private static func points(
        from recognized: [VNHumanHandPoseObservation.JointName: VNRecognizedPoint]
    ) -> [HandPoint?] {
        jointOrder.map { name in
            guard let point = recognized[name], point.confidence > 0.3 else { return nil }
            return HandPoint(x: Double(point.location.x), y: Double(point.location.y))
        }
    }
}

/// The live camera picture in the menu-bar panel.
struct CameraPreviewView: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let container = NSView()
        container.wantsLayer = true

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = container.bounds
        preview.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        container.layer?.addSublayer(preview)
        return container
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        guard let layer = nsView.layer?.sublayers?.first as? AVCaptureVideoPreviewLayer else { return }
        if layer.session !== session {
            layer.session = session
        }
    }
}
