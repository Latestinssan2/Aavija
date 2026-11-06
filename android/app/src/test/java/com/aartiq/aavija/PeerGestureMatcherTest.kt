package com.aartiq.aavija

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-to-phone gesture table: one side GRABS, the other RELEASES within
 * ten seconds, and the connecting phone drives the matching transfer.
 */
class PeerGestureMatcherTest {

    private fun offer(type: String, id: Long, ageMs: Long = 0L) =
        GestureOffer(type = type, id = id, ageMs = ageMs)

    @Test
    fun `our grab plus their release sends our file`() {
        val matcher = PeerGestureMatcher()

        assertNull(matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000))
        assertEquals(
            PeerGestureMatcher.Action.SEND_TO_PEER,
            matcher.onRemote(offer("release", 7), nowMs = 4_000),
        )
    }

    @Test
    fun `their grab plus our release pulls their file`() {
        val matcher = PeerGestureMatcher()

        assertNull(matcher.onRemote(offer("grab", 3), nowMs = 1_000))
        assertEquals(
            PeerGestureMatcher.Action.PULL_FROM_PEER,
            matcher.onLocal(PeerGestureMatcher.Kind.RELEASE, 4_000),
        )
    }

    @Test
    fun `a release that arrives before their grab still counts once the grab lands`() {
        val matcher = PeerGestureMatcher()
        assertNull(matcher.onLocal(PeerGestureMatcher.Kind.RELEASE, 2_000))

        // Their grab happened 1s before our release, so it is in the window.
        assertEquals(
            PeerGestureMatcher.Action.PULL_FROM_PEER,
            matcher.onRemote(offer("grab", 9, ageMs = 3_000), nowMs = 4_000),
        )
    }

    @Test
    fun `outside the ten second window nothing happens`() {
        val matcher = PeerGestureMatcher()
        matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000)

        assertNull(
            matcher.onRemote(
                offer("release", 1, ageMs = 0),
                nowMs = 1_000 + PeerGestureMatcher.WINDOW_MS + 1,
            ),
        )
    }

    @Test
    fun `exactly ten seconds is still inside the window`() {
        val matcher = PeerGestureMatcher()
        matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000)

        assertEquals(
            PeerGestureMatcher.Action.SEND_TO_PEER,
            matcher.onRemote(
                offer("release", 1, ageMs = 0),
                nowMs = 1_000 + PeerGestureMatcher.WINDOW_MS,
            ),
        )
    }

    @Test
    fun `a repeated heartbeat gesture id cannot fire twice`() {
        val matcher = PeerGestureMatcher()
        matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000)

        assertEquals(
            PeerGestureMatcher.Action.SEND_TO_PEER,
            matcher.onRemote(offer("release", 5), nowMs = 2_000),
        )
        assertNull(matcher.onRemote(offer("release", 5), nowMs = 3_000))
    }

    @Test
    fun `one grab release pair fires only one transfer`() {
        val matcher = PeerGestureMatcher()
        matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000)
        assertEquals(
            PeerGestureMatcher.Action.SEND_TO_PEER,
            matcher.onRemote(offer("release", 1), nowMs = 2_000),
        )

        // A second release (different id) must not reuse the same local grab.
        assertNull(matcher.onRemote(offer("release", 2), nowMs = 3_000))
    }

    @Test
    fun `a release with no grab does nothing`() {
        val matcher = PeerGestureMatcher()
        assertNull(matcher.onRemote(offer("release", 4), nowMs = 2_000))
    }

    @Test
    fun `reset forgets both sides`() {
        val matcher = PeerGestureMatcher()
        matcher.onLocal(PeerGestureMatcher.Kind.GRAB, 1_000)
        matcher.onRemote(offer("grab", 1), nowMs = 1_500)

        matcher.reset()

        assertNull(matcher.onLocal(PeerGestureMatcher.Kind.RELEASE, 2_000))
        assertNull(matcher.onRemote(offer("release", 2), nowMs = 2_500))
        assertTrue(matcher.isNew(offer("grab", 1)))
        assertFalse(matcher.isNew(offer("release", 2)))
        assertTrue(matcher.isNew(offer("release", 3)))
    }

    @Test
    fun `isNew only reports ids we have not recorded`() {
        val matcher = PeerGestureMatcher()
        assertTrue(matcher.isNew(offer("grab", 1)))

        matcher.onRemote(offer("grab", 1), nowMs = 1_000)
        assertFalse(matcher.isNew(offer("grab", 1)))
        assertTrue(matcher.isNew(offer("grab", 2)))
    }
}
