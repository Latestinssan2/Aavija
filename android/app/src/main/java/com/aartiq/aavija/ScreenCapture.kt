package com.aartiq.aavija

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot screenshots through Android's MediaProjection API.
 *
 * The user grants screen capture once (the familiar system dialog). While
 * Gesture Mode is on, we keep the projection alive and create a VirtualDisplay
 * only for the moment a GRAB needs a frame — nothing is recorded, streamed, or
 * written outside the app cache. The PNG lives in memory only long enough to
 * become an [OutgoingFile], exactly like the Mac's screenshot staging.
 *
 * On Android 14+ this only works while a foreground service with the
 * `mediaProjection` type is running. [GestureShareService] starts that type
 * before calling [start].
 */
class ScreenCaptureController(private val context: Context) {

    data class StagedScreenshot(
        val file: File,
        val name: String,
        val size: Long,
        val sha256Hex: String,
    )

    companion object {
        private const val TAG = "AavijaScreenshot"
        private const val CAPTURE_TIMEOUT_SECONDS = 4L
        private const val CACHE_DIR = "screenshots"
    }

    private val _active = MutableStateFlow(false)
    /** True while a valid MediaProjection is held. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var projection: MediaProjection? = null

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var captureExecutor: ExecutorService? = null

    @Volatile
    private var capturing = false

    /** Did the user grant screen capture, and is the projection still alive? */
    fun isActive(): Boolean = _active.value

    /** Is a screenshot already being taken? */
    fun isCapturing(): Boolean = capturing

    /**
     * Start a projection from the result of the system consent dialog.
     * Returns true when the projection is ready for captures.
     */
    @Synchronized
    fun start(resultCode: Int, data: Intent?): Boolean {
        if (_active.value) return true
        if (resultCode != Activity.RESULT_OK || data == null) {
            _lastError.value = "Screen capture permission was not granted."
            return false
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (manager == null) {
            _lastError.value = "This device cannot share the screen."
            return false
        }

        val newProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            _lastError.value = "Android refused screen capture: ${e.message ?: "unknown error"}"
            return false
        }
        if (newProjection == null) {
            _lastError.value = "Screen capture permission was not granted."
            return false
        }

        // Android 14+ requires a callback BEFORE the first VirtualDisplay.
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system/user")
                projection = null
                _active.value = false
                _lastError.value = "Screen sharing stopped. Turn Gesture Mode off and on to allow screenshots again."
            }
        }, mainHandler)

        projection = newProjection
        _lastError.value = null
        _active.value = true
        return true
    }

    /**
     * Capture the current display. [onResult] is always called on the main
     * thread. Returns false when a capture could not be started (the caller
     * can then report the gesture normally instead of waiting).
     */
    fun capture(onResult: (StagedScreenshot?, String?) -> Unit): Boolean {
        val activeProjection = projection
        if (activeProjection == null) {
            deliverError(onResult, "Screen capture is off. Enable it in Gesture Mode first.")
            return false
        }
        if (capturing) return false

        capturing = true
        val executor = ensureCaptureExecutor()
        if (executor == null) {
            capturing = false
            deliverError(onResult, "Couldn't start the screenshot worker.")
            return false
        }

        try {
            executor.execute {
                val result = runCatching { captureBlocking(activeProjection) }
                mainHandler.post {
                    capturing = false
                    result.fold(
                        onSuccess = { onResult(it, null) },
                        onFailure = { error ->
                            val message = error.message ?: "Couldn't capture the screen."
                            _lastError.value = message
                            onResult(null, message)
                        },
                    )
                }
            }
        } catch (e: Exception) {
            capturing = false
            deliverError(onResult, "Couldn't start the screenshot: ${e.message}")
            return false
        }
        return true
    }

    private fun deliverError(
        onResult: (StagedScreenshot?, String?) -> Unit,
        message: String,
    ) {
        mainHandler.post {
            _lastError.value = message
            onResult(null, message)
        }
    }

    private fun captureBlocking(mediaProjection: MediaProjection): StagedScreenshot {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_LOW)

        ensureImageThread()
        val handler = imageHandler ?: throw IllegalStateException("Screenshot worker is not ready")
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val frame = AtomicReference<Image?>(null)
        val latch = CountDownLatch(1)

        var virtualDisplay: VirtualDisplay? = null
        var bitmap: Bitmap? = null
        try {
            reader.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
                if (image != null && frame.compareAndSet(null, image)) {
                    latch.countDown()
                } else {
                    image?.close()
                }
            }, handler)

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "AavijaScreenshot",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )

            if (!latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException("The screen didn't produce a screenshot in time.")
            }

            val image = frame.getAndSet(null)
                ?: throw IllegalStateException("The screenshot frame was empty.")
            try {
                bitmap = imageToBitmap(image, width, height)
            } finally {
                image.close()
            }

            val png = bitmap ?: throw IllegalStateException("The screenshot couldn't be encoded.")
            val file = File(cacheDirectory(), filename())
            FileOutputStream(file).use { out ->
                if (!png.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("The screenshot couldn't be encoded.")
                }
            }
            if (!file.exists() || file.length() <= 0L) {
                file.delete()
                throw IllegalStateException("The screenshot file was empty.")
            }

            val size = file.length()
            val sha = OutgoingQueue.sha256 { FileInputStream(file) }
            Log.i(TAG, "Captured ${file.name} (${size} bytes)")
            return StagedScreenshot(file, file.name, size, sha)
        } finally {
            frame.getAndSet(null)?.close()
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            try {
                reader.setOnImageAvailableListener(null, null)
                reader.close()
            } catch (_: Exception) {
            }
            bitmap?.recycle()
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        // ImageReader pads each row for hardware alignment; include the padding
        // in the bitmap, then crop it back to the real screen width.
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun cacheDirectory(): File =
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    private fun filename(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date())
        return "Aavija-Screenshot-$stamp.png"
    }

    private fun ensureImageThread(): HandlerThread {
        val existing = imageThread
        if (existing != null && existing.isAlive) return existing
        return HandlerThread("AavijaScreenshotFrames").also {
            it.start()
            imageThread = it
            imageHandler = Handler(it.looper)
        }
    }

    private fun ensureCaptureExecutor(): ExecutorService? {
        val existing = captureExecutor
        if (existing != null && !existing.isShutdown) return existing
        return try {
            Executors.newSingleThreadExecutor().also { captureExecutor = it }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create screenshot executor", e)
            null
        }
    }

    /** Release the projection and stop the worker threads. */
    @Synchronized
    fun stop() {
        _active.value = false
        capturing = false

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        captureExecutor?.shutdownNow()
        captureExecutor = null

        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
    }
}
