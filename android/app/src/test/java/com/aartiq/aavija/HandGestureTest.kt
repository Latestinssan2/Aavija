package com.aartiq.aavija

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 5: the gesture rules that decide whether a file moves.
 *
 * These matter more than most tests in this repo: a false GRAB/RELEASE is a
 * file leaving the phone without the user meaning it. The synthetic hands below
 * are built from the same geometry MediaPipe reports, so the classifier is
 * exercised without a camera.
 */
class HandGestureTest {

    /**
     * Build 21 points shaped like a hand.
     *
     * The wrist sits at the bottom and the four fingers point up. `extended`
     * fingers put their tip far above their PIP joint; curled ones tuck the tip
     * back toward the wrist. Thumb points are present (MediaPipe always sends
     * them) but deliberately ignored by the classifier.
     */
    private fun hand(extendedFingers: Int): List<HandPoint> {
        val points = MutableList(21) { HandPoint(0.5f, 0.5f) }
        points[0] = HandPoint(0.5f, 0.90f) // wrist

        // Finger tuples: (mcp, pip, dip, tip)
        val fingers = listOf(
            listOf(5, 6, 7, 8),
            listOf(9, 10, 11, 12),
            listOf(13, 14, 15, 16),
            listOf(17, 18, 19, 20),
        )
        fingers.forEachIndexed { i, (mcp, pip, dip, tip) ->
            val x = 0.40f + 0.05f * i
            points[mcp] = HandPoint(x, 0.72f)
            points[pip] = HandPoint(x, 0.62f)
            if (i < extendedFingers) {
                points[dip] = HandPoint(x, 0.50f)
                points[tip] = HandPoint(x, 0.38f) // far from the wrist → extended
            } else {
                points[dip] = HandPoint(x, 0.74f)
                points[tip] = HandPoint(x, 0.82f) // curled back toward the wrist
            }
        }
        // Thumb (ignored on purpose).
        points[1] = HandPoint(0.40f, 0.85f)
        points[2] = HandPoint(0.36f, 0.80f)
        points[3] = HandPoint(0.32f, 0.76f)
        points[4] = HandPoint(0.28f, 0.72f)
        return points
    }

    // ---- Single-frame classification --------------------------------------

    @Test
    fun `four extended fingers are open`() {
        assertEquals(HandPose.OPEN, HandGestureMachine.classify(hand(4)))
    }

    @Test
    fun `three extended fingers are open`() {
        assertEquals(HandPose.OPEN, HandGestureMachine.classify(hand(3)))
    }

    @Test
    fun `no extended fingers is a fist`() {
        assertEquals(HandPose.FIST, HandGestureMachine.classify(hand(0)))
    }

    @Test
    fun `one extended finger is still a fist`() {
        assertEquals(HandPose.FIST, HandGestureMachine.classify(hand(1)))
    }

    @Test
    fun `two extended fingers is ambiguous`() {
        assertEquals(HandPose.UNKNOWN, HandGestureMachine.classify(hand(2)))
    }

    @Test
    fun `a missing hand is unknown`() {
        assertEquals(HandPose.UNKNOWN, HandGestureMachine.classify(emptyList()))
        assertEquals(HandPose.UNKNOWN, HandGestureMachine.classify(hand(4).take(10)))
    }

    // ---- Transitions ------------------------------------------------------

    private fun feed(
        machine: HandGestureMachine,
        frames: Int,
        extended: Int,
        startMs: Long,
    ): HandGestureMachine.Event? {
        var event: HandGestureMachine.Event? = null
        for (i in 0 until frames) {
            event = machine.onFrame(hand(extended), startMs + i * 100L) ?: event
        }
        return event
    }

    @Test
    fun `open then fist is a grab`() {
        val m = HandGestureMachine()
        feed(m, frames = 5, extended = 4, startMs = 1_000)

        val e = feed(m, frames = 5, extended = 0, startMs = 1_500)
        assertEquals(HandGestureMachine.EventType.GRAB, e?.type)
    }

    @Test
    fun `fist then open is a release`() {
        val m = HandGestureMachine()
        feed(m, frames = 5, extended = 0, startMs = 1_000)

        val e = feed(m, frames = 5, extended = 4, startMs = 1_500)
        assertEquals(HandGestureMachine.EventType.RELEASE, e?.type)
    }

    @Test
    fun `the first settled pose is not an event`() {
        // The very first thing the camera sees must not look like a transition:
        // starting with a fist is not a GRAB.
        val m = HandGestureMachine()
        assertNull(feed(m, frames = 5, extended = 0, startMs = 1_000))
    }

    @Test
    fun `a one-frame flicker causes no event`() {
        val m = HandGestureMachine()
        feed(m, frames = 5, extended = 4, startMs = 1_000)

        // One fist frame, then back to open: not 4-of-5, so nothing happens.
        m.onFrame(hand(0), 1_500)
        val e = feed(m, frames = 4, extended = 4, startMs = 1_600)
        assertNull(e)
        assertEquals(HandPose.OPEN, m.currentPose)
    }

    @Test
    fun `half-open frames do not change the settled pose`() {
        val m = HandGestureMachine()
        feed(m, frames = 5, extended = 4, startMs = 1_000)

        val e = feed(m, frames = 5, extended = 2, startMs = 1_500)
        assertNull(e)
        assertEquals(HandPose.OPEN, m.currentPose)
    }

    @Test
    fun `reset forgets the pose so the next grab needs a full window`() {
        val m = HandGestureMachine()
        feed(m, frames = 5, extended = 4, startMs = 1_000)
        m.reset()

        // A fist immediately after reset is the FIRST pose, not a transition.
        assertNull(feed(m, frames = 5, extended = 0, startMs = 2_000))
    }

    @Test
    fun `events respect the minimum hold time`() {
        val m = HandGestureMachine(minHoldMs = 2_000)
        feed(m, frames = 5, extended = 4, startMs = 1_000)

        // Grab at ~1.5 s, then release 0.5 s later: too fast to be deliberate.
        val grab = feed(m, frames = 5, extended = 0, startMs = 1_500)
        assertEquals(HandGestureMachine.EventType.GRAB, grab?.type)
        val release = feed(m, frames = 5, extended = 4, startMs = 2_000)
        assertNull(release)
    }
}
