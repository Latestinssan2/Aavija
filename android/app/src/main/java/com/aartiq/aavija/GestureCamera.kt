package com.aartiq.aavija

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera → MediaPipe hand landmarks → [HandGestureMachine].
 *
 * The controller owns its own [Lifecycle] instead of borrowing the activity's.
 * That is the key change that lets [GestureShareService] keep the camera and
 * gesture detection alive while the UI is backgrounded: the activity only
 * attaches a preview surface when it is visible, and detaches it again when it
 * goes away. The analysis use case never stops until Gesture Mode is turned
 * off.
 *
 * Frames are throttled to ~10 fps on purpose — the state machine is tuned for
 * that rate, and running the landmarker at 30 fps would just burn battery to
 * produce the same five-frame window faster. Frames are classified in memory
 * and discarded immediately; nothing is recorded or transmitted.
 */
class GestureCameraController(private val context: Context) : LifecycleOwner {

    companion object {
        private const val TAG = "AavijaGesture"

        /** ~10 fps: the rate HandGestureMachine's 5-frame window is tuned for. */
        private const val FRAME_INTERVAL_MS = 100L

        /** How long with no hand before we forget the pose (avoid phantom events). */
        private const val HAND_LOST_MS = 800L
    }

    private var registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Set by [AavijaRuntime]; receives every settled GRAB/RELEASE. */
    var onEvent: (HandGestureMachine.EventType) -> Unit = {}

    private val _pose = MutableStateFlow(HandPose.UNKNOWN)
    val pose: StateFlow<HandPose> = _pose.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val machine = HandGestureMachine()

    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var previewSurface: Preview.SurfaceProvider? = null
    private var landmarker: HandLandmarker? = null
    private var lastAnalysisAt = 0L
    private var lastHandAt = 0L

    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    /**
     * Offscreen dummy surface provider that keeps the CameraX capture session
     * continuously alive in the background when the activity is minimized,
     * preventing Android OS from revoking background camera access.
     */
    private val dummySurfaceProvider = Preview.SurfaceProvider { request ->
        val texture = SurfaceTexture(0).also { dummySurfaceTexture = it }
        texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(texture).also { dummySurface = it }
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
            surface.release()
            texture.release()
            if (dummySurface == surface) dummySurface = null
            if (dummySurfaceTexture == texture) dummySurfaceTexture = null
        }
    }

    /** Increments on every start/release so old camera callbacks are ignored. */
    private var generation = 0

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** Start the camera + landmarker. Idempotent. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        _error.value = null
        machine.reset()
        _pose.value = HandPose.UNKNOWN
        analysisExecutor = Executors.newSingleThreadExecutor()
        registry = LifecycleRegistry(this).also {
            it.currentState = Lifecycle.State.STARTED
        }
        createLandmarker(gen)
        startCamera(gen)
    }

    /**
     * Attach a visible preview surface. Camera analysis is already running in
     * the service; this only decides whether the user sees frames.
     */
    @Synchronized
    fun attachPreview(previewView: PreviewView) {
        previewSurface = previewView.surfaceProvider
        try {
            previewUseCase?.setSurfaceProvider(ContextCompat.getMainExecutor(context), previewSurface)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set surface provider on attach", e)
        }
        if (!running) start()
    }

    /**
     * The UI minimized or left; switch to the offscreen dummy surface so
     * CameraX keeps the session continuously active in the background
     * without tearing down the Camera2 device.
     */
    @Synchronized
    fun detachPreview() {
        previewSurface = null
        try {
            previewUseCase?.setSurfaceProvider(ContextCompat.getMainExecutor(context), dummySurfaceProvider)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set offscreen dummy surface on detach", e)
        }
    }

    /** Stop the camera and release MediaPipe. Safe to call repeatedly. */
    @Synchronized
    fun release() {
        if (!running) return
        running = false
        generation += 1

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        previewUseCase?.setSurfaceProvider(null)
        previewUseCase = null
        previewSurface = null

        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null

        analysisExecutor?.shutdownNow()
        analysisExecutor = null

        try {
            registry.currentState = Lifecycle.State.DESTROYED
        } catch (_: Exception) {
        }

        machine.reset()
        _pose.value = HandPose.UNKNOWN
        Log.i(TAG, "Gesture camera stopped")
    }

    private fun createLandmarker(gen: Int) {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> if (gen == generation) onResult(result) }
            .setErrorListener { error ->
                if (gen == generation) {
                    postError(error.message ?: "hand detection failed")
                }
            }
            .build()

        try {
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the hand model", e)
            postError("Gesture model failed to load: ${e.message}")
        }
    }

    private fun startCamera(gen: Int) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running || gen != generation) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also { useCase ->
                    val surfaceProvider = previewSurface ?: dummySurfaceProvider
                    useCase.setSurfaceProvider(ContextCompat.getMainExecutor(context), surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
                analysis.setAnalyzer(analysisExecutor ?: return@addListener) { image ->
                    analyze(image, gen)
                }

                previewUseCase = preview
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (frontError: Exception) {
                    // A device without a selfie camera (or with it in use) should
                    // still get gesture mode from the rear lens.
                    Log.w(TAG, "Front camera unavailable, trying the back one", frontError)
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
                registry.currentState = Lifecycle.State.RESUMED
                Log.i(TAG, "Gesture camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the camera", e)
                postError("Camera unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy, gen: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAt < FRAME_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysisAt = now
        try {
            val detector = landmarker ?: return
            // toBitmap() asks CameraX for an upright, rotated bitmap, so the
            // landmark coordinates match what the user sees in the preview.
            val bitmap = image.toBitmap()
            detector.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        } catch (e: Exception) {
            Log.w(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun onResult(result: HandLandmarkerResult) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val hand = result.landmarks().firstOrNull()

        if (hand.isNullOrEmpty()) {
            // No hand for a moment is normal (blink, fast motion). Only reset
            // after a clear absence, so a single dropped frame can't fabricate
            // a GRAB/RELEASE transition.
            if (lastHandAt != 0L && now - lastHandAt > HAND_LOST_MS) {
                lastHandAt = 0L
                machine.reset()
                _pose.value = HandPose.UNKNOWN
            }
            return
        }

        lastHandAt = now
        val points = hand.map { HandPoint(it.x(), it.y(), it.z()) }
        val event = machine.onFrame(points, now)
        if (event != null) {
            Log.i(TAG, "Gesture: ${event.type}")
            mainHandler.post {
                if (running) onEvent(event.type)
            }
        }
        _pose.value = machine.currentPose
    }

    private fun postError(message: String) {
        mainHandler.post {
            if (running) _error.value = message
        }
    }
}

/**
 * The live preview. Camera ownership stays with [GestureCameraController] (and
 * therefore with the foreground service); this composable only lends its
 * surface while it is on screen.
 */
@Composable
fun GestureCameraPreview(
    controller: GestureCameraController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                controller.attachPreview(view)
            }
        },
    )

    DisposableEffect(controller) {
        onDispose { controller.detachPreview() }
    }
}
