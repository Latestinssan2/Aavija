package com.preet.aavija

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Aavija's short UI sounds.
 *
 * The three WAVs in `res/raw` are synthesized offline, so there is no network
 * fetch, no third-party sound library, and no playback file on disk at runtime.
 * SoundPool is the right tool for short, overlapping effects.
 *
 * Sounds are best-effort: if audio is unavailable, transfers still work.
 */
object AavijaSounds {

    private var pool: SoundPool? = null
    private var captureId = 0
    private var startId = 0
    private var completeId = 0

    /** Call once from the activity, before any transfer can happen. */
    @Synchronized
    fun init(context: Context) {
        if (pool != null) return

        val soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()

        val app = context.applicationContext
        captureId = soundPool.load(app, R.raw.capture, 1)
        startId = soundPool.load(app, R.raw.transfer_start, 1)
        completeId = soundPool.load(app, R.raw.transfer_complete, 1)
        pool = soundPool
    }

    /** A screenshot was captured (currently Mac-only, kept for completeness). */
    fun screenshot() = play(captureId, 0.85f)

    /** Bytes are about to move. */
    fun transferStart() = play(startId, 0.55f)

    /** A transfer finished successfully. */
    fun transferComplete() = play(completeId, 0.70f)

    private fun play(id: Int, volume: Float) {
        if (id == 0) return
        runCatching {
            pool?.play(id, volume, volume, 1, 0, 1f)
        }
    }
}
