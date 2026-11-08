package com.aartiq.aavija

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps gesture sharing alive after the activity leaves the foreground.
 *
 * Two Android rules shape this class:
 *
 *  - Camera: from Android 14 a camera-in-the-background app must run a
 *    foreground service with the `camera` type and the
 *    FOREGROUND_SERVICE_CAMERA permission.
 *  - Screenshots: MediaProjection must be used from a foreground service with
 *    the `mediaProjection` type, and that type must be active *before*
 *    MediaProjectionManager.getMediaProjection() is called.
 *
 * We therefore start with only the camera type (always legal from the visible
 * app), then upgrade to camera + mediaProjection when the consent result comes
 * back from the system dialog. Nothing here records the screen; the projection
 * is used for one frame per GRAB.
 */
class GestureShareService : Service() {

    companion object {
        private const val TAG = "AavijaService"

        const val ACTION_START = "com.aartiq.aavija.action.START_GESTURE"
        const val ACTION_STOP = "com.aartiq.aavija.action.STOP_GESTURE"
        const val ACTION_PROJECTION = "com.aartiq.aavija.action.PROJECTION"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "aavija_gesture"
        private const val NOTIFICATION_ID = 4201

        /** Start (or reassert) the background session. */
        fun start(context: Context) {
            val intent = Intent(context, GestureShareService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop the background session and release the camera/projection. */
        fun stop(context: Context) {
            // stopService (not startService) works from any lifecycle state and
            // lets onDestroy do the cleanup exactly once.
            context.stopService(Intent(context, GestureShareService::class.java))
        }

        /**
         * Hand the result of the system screen-capture dialog to the service,
         * which upgrades its foreground type before creating the projection.
         */
        fun grantProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, GestureShareService::class.java)
                .setAction(ACTION_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var runtime: AavijaRuntime
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "aavija:gesture_camera").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1 hr max safeguard
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AavijaRuntime.get(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // startForegroundService contract: still call startForeground,
                // then leave immediately. onDestroy releases the camera.
                startForegroundWith(includeProjection = false)
                stopSelf()
            }

            ACTION_PROJECTION -> {
                // The mediaProjection type must be live before getMediaProjection.
                startForegroundWith(includeProjection = true)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = projectionResultData(intent)
                if (runtime.screenCapture.start(code, data)) {
                    updateNotification(includeProjection = true)
                } else {
                    // Permission was denied or Android rejected the token:
                    // keep scanning gestures for file sharing, just without
                    // screenshots.
                    startForegroundWith(includeProjection = false)
                }
            }

            null -> {
                // Restarted by the system without our intent. Reassert the
                // session so the notification matches reality.
                startForegroundWith(includeProjection = false)
                runtime.onServiceStarted()
            }

            else -> {
                startForegroundWith(includeProjection = runtime.screenCapture.isActive())
                runtime.onServiceStarted()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        runtime.onServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun projectionResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun startForegroundWith(includeProjection: Boolean) {
        acquireWakeLock()
        val notification = buildNotification(includeProjection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (includeProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(includeProjection: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(includeProjection))
    }

    private fun buildNotification(includeProjection: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val turnOff = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, GestureShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = if (includeProjection) {
            "Camera + screen capture ready — GRAB to capture, RELEASE to share"
        } else {
            "Camera scanning for gestures — open Aavija to enable screenshots"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gesture)
            .setContentTitle("Aavija gesture sharing")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off",
                turnOff,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gesture sharing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps Aavija's camera and gesture sharing running in the background."
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}
