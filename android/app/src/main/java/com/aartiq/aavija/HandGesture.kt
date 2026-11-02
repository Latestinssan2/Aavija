package com.aartiq.aavija

import kotlin.math.hypot

/**
 * Phase 5: one hand point as MediaPipe reports it — normalized to the camera
 * frame, so (0,0) is top-left and (1,1) is bottom-right. `z` is relative depth
 * (smaller = closer); we don't use it for the pose decision because it is noisy
 * on phone cameras, but it stays here for future refinement.
 */
data class HandPoint(val x: Float, val y: Float, val z: Float = 0f)

/** What the hand looks like right now. UNKNOWN is a real state: half-curled. */
enum class HandPose { OPEN, FIST, UNKNOWN }

/**
 * Phase 7: one gesture as it travels to the other device.
 *
 * [ageMs] is measured on the REPORTER's clock, not an absolute timestamp. The
 * two devices never need synchronised clocks: the receiver subtracts the age
 * from its own "now" to estimate when the gesture happened.
 *
 * [id] increases with every gesture, so a receiver can ignore the same event
 * being repeated on consecutive heartbeats.
 */
data class GestureOffer(
    val type: String, // "grab" or "release"
    val id: Long,
    val ageMs: Long,
)

/**
 * Remembers this device's latest gesture and offers it to the peer for as long
 * as it can still match the other device's gesture.
 *
 * Only the latest event matters: if the user grabs, releases, then grabs again,
 * the older events are already outside the window or were superseded.
 */
class GestureReporter(
    private val clock: () -> Long,
    private val windowMs: Long = MATCH_WINDOW_MS,
) {
    companion object {
        /** How long a grab and the other device's release may be apart. */
        const val MATCH_WINDOW_MS = 10_000L
    }

    private var nextId = 0L
    private var lastType: String? = null
    private var lastAtMs = 0L
    private var lastId = 0L

    /** Record a locally detected gesture. Thread-safe: camera and network differ. */
    @Synchronized
    fun record(type: HandGestureMachine.EventType, atMs: Long = clock()) {
        nextId += 1
        lastType = when (type) {
            HandGestureMachine.EventType.GRAB -> "grab"
            HandGestureMachine.EventType.RELEASE -> "release"
        }
        lastAtMs = atMs
        lastId = nextId
    }

    /** The event to put on the wire, or null once it is too old to match. */
    @Synchronized
    fun offer(nowMs: Long = clock()): GestureOffer? {
        val type = lastType ?: return null
        val age = nowMs - lastAtMs
        if (age < 0 || age > windowMs) return null
        return GestureOffer(type = type, id = lastId, ageMs = age)
    }

    /** Forget everything (gesture mode turned off / camera stopped). */
    @Synchronized
    fun reset() {
        lastType = null
        lastAtMs = 0L
        lastId = 0L
    }
}

/**
 * The gesture state machine: frames in, GRAB/RELEASE events out.
 *
 * WHY NOT CLASSIFY EVERY FRAME INDEPENDENTLY? A hand in motion is ambiguous for
 * a frame or two — fingers blur, the model flickers. Acting on that would send a
 * file because someone scratched their nose. So we:
 *
 *  1. classify each frame as OPEN / FIST / UNKNOWN (see [classify]),
 *  2. keep a short window (5 frames ≈ 0.5 s at the 10 fps we feed it),
 *  3. only accept a pose when 4 of those 5 agree,
 *  4. emit an event only on a real transition, with a minimum hold time.
 *
 * GRAB = OPEN → FIST. RELEASE = FIST → OPEN. Anything else is not a gesture.
 */
class HandGestureMachine(
    private val windowSize: Int = 5,
    private val minStableFrames: Int = 4,
    private val minHoldMs: Long = 500,
) {
    enum class EventType { GRAB, RELEASE }
    data class Event(val type: EventType, val atMs: Long)

    private val recent = ArrayDeque<HandPose>()
    private var pose: HandPose = HandPose.UNKNOWN
    private var lastEventAt: Long? = null

    /** The settled pose (after smoothing), for the UI to show. */
    val currentPose: HandPose get() = pose

    /** Forget everything — call when the hand leaves the frame. */
    fun reset() {
        recent.clear()
        pose = HandPose.UNKNOWN
        lastEventAt = null
    }

    /**
     * Feed one analysed frame. Returns the event it caused, or null. `atMs` is
     * a monotonic-ish timestamp (SystemClock/System.currentTimeMillis); only
     * differences matter.
     */
    fun onFrame(points: List<HandPoint>, atMs: Long): Event? {
        recent.addLast(classify(points))
        while (recent.size > windowSize) recent.removeFirst()
        if (recent.size < windowSize) return null

        val fists = recent.count { it == HandPose.FIST }
        val opens = recent.count { it == HandPose.OPEN }
        val decided = when {
            fists >= minStableFrames -> HandPose.FIST
            opens >= minStableFrames -> HandPose.OPEN
            else -> HandPose.UNKNOWN
        }
        if (decided == HandPose.UNKNOWN || decided == pose) return null

        val previous = pose
        pose = decided
        val type = when {
            previous == HandPose.OPEN && decided == HandPose.FIST -> EventType.GRAB
            previous == HandPose.FIST && decided == HandPose.OPEN -> EventType.RELEASE
            // First-ever settled pose: no transition to report.
            else -> return null
        }
        if (lastEventAt?.let { atMs - it < minHoldMs } == true) return null
        lastEventAt = atMs
        return Event(type, atMs)
    }

    companion object {
        // Index/middle/ring/pinky: (fingertip, PIP joint). A finger is extended
        // when its tip is clearly farther from the wrist than the PIP is.
        private val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)

        /** Ratio below which a fingertip counts as curled. */
        private const val EXTENDED_RATIO = 1.20f

        /**
         * Classify a single frame from MediaPipe's 21 landmarks.
         *
         * Distances are measured as ratios (tip-to-wrist / pip-to-wrist), so the
         * result does not change with hand size, distance from the camera, or
         * how the phone is held.
         *
         *  - 3–4 extended fingers → OPEN
         *  - 0–1 extended fingers → FIST
         *  - exactly 2         → UNKNOWN (half-open, e.g. peace sign)
         */
        fun classify(points: List<HandPoint>): HandPose {
            if (points.size < 21) return HandPose.UNKNOWN
            val wrist = points[0]
            var extended = 0
            for ((tip, pip) in FINGERS) {
                val tipDist = dist(points[tip], wrist)
                val pipDist = dist(points[pip], wrist)
                if (pipDist <= 0.0001f) continue
                if (tipDist > pipDist * EXTENDED_RATIO) extended++
            }
            return when {
                extended >= 3 -> HandPose.OPEN
                extended <= 1 -> HandPose.FIST
                else -> HandPose.UNKNOWN
            }
        }

        private fun dist(a: HandPoint, b: HandPoint): Float =
            hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }
}
