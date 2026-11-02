package com.preet.aavija

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 7: the phone's half of the gesture handshake.
 *
 * The offer is deliberately relative (age, not a wall-clock timestamp), because
 * the phone and Mac clocks are not synchronised. These tests pin down that it
 * expires after 10 seconds and that a repeated heartbeat does not look like a
 * new gesture.
 */
class GestureReporterTest {

    private var now = 1_000L
    private fun reporter() = GestureReporter(clock = { now })

    @Test
    fun `a fresh grab is offered with its age`() {
        val r = reporter()
        now = 1_000
        r.record(HandGestureMachine.EventType.GRAB)
        now = 1_250

        val offer = r.offer()
        assertEquals("grab", offer?.type)
        assertEquals(250L, offer?.ageMs)
        assertEquals(1L, offer?.id)
    }

    @Test
    fun `a release replaces the grab`() {
        val r = reporter()
        now = 1_000
        r.record(HandGestureMachine.EventType.GRAB)
        now = 1_500
        r.record(HandGestureMachine.EventType.RELEASE)
        now = 1_750

        val offer = r.offer()
        assertEquals("release", offer?.type)
        assertEquals(250L, offer?.ageMs)
        assertEquals(2L, offer?.id)
    }

    @Test
    fun `the same event keeps the same id across heartbeats`() {
        val r = reporter()
        now = 1_000
        r.record(HandGestureMachine.EventType.GRAB)
        now = 1_500
        val first = r.offer()
        now = 3_000
        val second = r.offer()
        assertEquals(first?.id, second?.id)
    }

    @Test
    fun `an offer expires after ten seconds`() {
        val r = reporter()
        now = 1_000
        r.record(HandGestureMachine.EventType.GRAB)
        now = 11_000
        assertEquals(10_000L, r.offer()?.ageMs)
        now = 11_001
        assertNull("11 s is too late to match a release", r.offer())
    }

    @Test
    fun `reset drops a pending gesture`() {
        val r = reporter()
        now = 1_000
        r.record(HandGestureMachine.EventType.GRAB)
        r.reset()
        assertNull(r.offer())
    }

    @Test
    fun `a clock that goes backwards does not report a negative age`() {
        val r = reporter()
        now = 5_000
        r.record(HandGestureMachine.EventType.GRAB)
        now = 4_000
        assertNull(r.offer())
    }
}
