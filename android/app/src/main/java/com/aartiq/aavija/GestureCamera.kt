package com.aartiq.aavija

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

/**
 * Phase 5: camera → MediaPipe hand landmarks → [HandGestureMachine].
 *
 * The camera exists ONLY while this panel is composed, and the panel only
 * exists while Gesture mode is ON (default OFF). There is no background
 * camera, no recording, and nothing leaves the device: [HandPoint]s are
 * classified locally and the frame is discarded immediately.
 *
 * Frames are throttled to ~10 fps on purpose — the state machine is tuned for
 * that rate, and running the landmarker at 30 fps would just burn battery to
 * produce the same five-frame window faster.
 */
@Composable
fun GestureCameraPreview(
    modifier: Modifier = Modifier,
    onPose: (HandPose) -> Unit,
    onEvent: (HandGestureMachine.EventType) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // rememberUpdatedState lets the controller keep running while the lambdas
    // change identity on recomposition (they close over mutable state).
    val poseCb = rememberUpdatedState(onPose)
    val eventCb = rememberUpdatedState(onEvent)
    val errorCb = rememberUpdatedState(onError)

    val controller = remember { CameraGestureController(context) }
    SideEffect {
        controller.onPose = { poseCb.value(it) }
        controller.onEvent = { eventCb.value(it) }
        controller.onError = { errorCb.value(it) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                controller.start(view, lifecycleOwner)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
}

/** Owns the CameraX session + MediaPipe landmarker for one Gesture-mode session. */
private class CameraGestureController(private val context: Context) {
    var onPose: (HandPose) -> Unit = {}
    var onEvent: (HandGestureMachine.EventType) -> Unit = {}
    var onError: (String) -> Unit = {}

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val machine = HandGestureMachine()

    private var cameraProvider: ProcessCameraProvider? = null
    private var landmarker: HandLandmarker? = null
    private var lastAnalysisAt = 0L
    private var lastHandAt = 0L
    private var released = false

    fun start(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        if (released || landmarker != null) return

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
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { error -> postError(error.message ?: "hand detection failed") }
            .build()

        try {
            landmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the hand model", e)
            postError("Gesture model failed to load: ${e.message}")
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                try {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (frontError: Exception) {
                    // A device without a selfie camera (or with it in use) should
                    // still get gesture mode from the rear lens.
                    Log.w(TAG, "Front camera unavailable, trying the back one", frontError)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
                Log.i(TAG, "Gesture camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the camera", e)
                postError("Camera unavailable: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
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
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val hand = result.landmarks().firstOrNull()

        if (hand.isNullOrEmpty()) {
            // No hand for a moment is normal (blink, fast motion). Only reset
            // after a clear absence, so a single dropped frame can't fabricate
            // a GRAB/RELEASE transition.
            if (lastHandAt != 0L && now - lastHandAt > HAND_LOST_MS) {
                lastHandAt = 0L
                machine.reset()
                postPose(HandPose.UNKNOWN)
            }
            return
        }

        lastHandAt = now
        val points = hand.map { HandPoint(it.x(), it.y(), it.z()) }
        val event = machine.onFrame(points, now)
        if (event != null) {
            Log.i(TAG, "Gesture: ${event.type}")
            mainHandler.post { onEvent(event.type) }
        }
        postPose(machine.currentPose)
    }

    private fun postPose(pose: HandPose) {
        mainHandler.post { if (!released) onPose(pose) }
    }

    private fun postError(message: String) {
        mainHandler.post { if (!released) onError(message) }
    }

    fun release() {
        released = true
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        try {
            landmarker?.close()
        } catch (_: Exception) {
        }
        landmarker = null
        analysisExecutor.shutdownNow()
        machine.reset()
        Log.i(TAG, "Gesture camera stopped")
    }

    private companion object {
        const val TAG = "AavijaGesture"

        /** ~10 fps: the rate HandGestureMachine's 5-frame window is tuned for. */
        const val FRAME_INTERVAL_MS = 100L

        /** How long with no hand before we forget the pose (avoid phantom events). */
        const val HAND_LOST_MS = 800L
    }
}
