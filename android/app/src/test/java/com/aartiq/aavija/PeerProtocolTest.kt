package com.aartiq.aavija

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android-to-Android wire shapes and the gesture-privacy rule.
 *
 * These run on the JVM, so a malformed line is caught in milliseconds instead
 * of during a two-phone pairing session on a desk.
 */
class PeerProtocolTest {

    @Test
    fun `pair message identifies a phone and carries a request id`() {
        val line = PeerProtocol.pairMessage("Pixel 7", "ab".repeat(32), "req-1")
        val obj = JSONObject(line)

        assertEquals("pair", obj.getString("type"))
        assertEquals("Pixel 7", obj.getString("name"))
        assertEquals("android", obj.getString("platform"))
        assertEquals("req-1", obj.getString("requestId"))
        assertTrue(PeerProtocol.isAndroidPair(line))
        assertEquals("req-1", PeerProtocol.requestId(line))
        assertEquals("Pixel 7", PeerProtocol.senderName(line))
    }

    @Test
    fun `a Mac pairing request is not mistaken for a phone request`() {
        val macPair = JSONObject()
            .put("type", "pair")
            .put("name", "Studio Mac")
            .put("proof", "ab".repeat(32))
            .toString()

        assertFalse(PeerProtocol.isAndroidPair(macPair))
        assertEquals("", PeerProtocol.requestId(macPair))
    }

    @Test
    fun `auth and ping lines carry the token`() {
        val token = "cd".repeat(32)
        val auth = PeerProtocol.authMessage(token)
        assertEquals(token, JSONObject(auth).getString("token"))
        assertEquals("android", JSONObject(auth).getString("platform"))
        assertTrue(PeerProtocol.isAndroidAuth(auth))
        assertFalse(PeerProtocol.isAndroidAuth(PeerProtocol.pingMessage(token)))
        assertEquals(token, JSONObject(PeerProtocol.pingMessage(token)).getString("token"))
        assertEquals("receive", JSONObject(PeerProtocol.receiveRequest()).getString("type"))
        assertEquals(
            "android",
            JSONObject(PeerProtocol.receiveRequest()).getString("platform"),
        )
    }

    @Test
    fun `cancel is only recognised for phones`() {
        assertTrue(PeerProtocol.isAndroidCancel(PeerProtocol.cancelMessage("req-9")))
        assertEquals("req-9", PeerProtocol.requestId(PeerProtocol.cancelMessage("req-9")))
        assertFalse(PeerProtocol.isAndroidCancel("""{"type":"cancel"}"""))
    }

    @Test
    fun `challenge salt is decoded, malformed challenges are refused`() {
        val salt = ByteArray(32) { it.toByte() }
        val line = JSONObject()
            .put("type", "challenge")
            .put("salt", PairingCrypto.toHex(salt))
            .toString()

        assertTrue(salt.contentEquals(PeerProtocol.parseChallengeSalt(line)))

        assertNull(PeerProtocol.parseChallengeSalt("""{"type":"challenge","salt":"zz"}"""))
        assertNull(PeerProtocol.parseChallengeSalt("""{"type":"challenge"}"""))
        assertNull(PeerProtocol.parseChallengeSalt("""{"type":"pong"}"""))
        assertNull(PeerProtocol.parseChallengeSalt("not json"))
        assertNull(PeerProtocol.parseChallengeSalt(null))
    }

    @Test
    fun `paired verdict keeps the 32-byte token`() {
        val verdict = PeerProtocol.parseVerdict(
            """{"type":"paired","token":"${"ef".repeat(32)}"}""",
        )

        assertNotNull(verdict)
        assertTrue(verdict!!.isPaired)
        assertEquals("ef".repeat(32), verdict.tokenHex)
    }

    @Test
    fun `rejected and locked verdicts keep the reason and retry delay`() {
        val rejected = PeerProtocol.parseVerdict(
            """{"type":"rejected","reason":"wrong pin"}""",
        )
        assertEquals("wrong pin", rejected?.reason)
        assertFalse(rejected!!.isPaired)

        val locked = PeerProtocol.parseVerdict(
            """{"type":"locked","retryAfterMs":240000}""",
        )
        assertEquals(240_000L, locked?.retryAfterMs)
    }

    @Test
    fun `pong carries gestures only when present and fresh`() {
        val line = JSONObject()
            .put("type", "pong")
            .put("outgoing", 2)
            .put("gestureGate", true)
            .put("gesture", "grab")
            .put("gestureId", 12)
            .put("gestureAgeMs", 1500)
            .toString()

        val pong = PeerProtocol.parsePong(line)
        assertNotNull(pong)
        assertEquals(2, pong!!.outgoing)
        assertTrue(pong.gestureGate)
        assertEquals("grab", pong.gesture?.type)
        assertEquals(12L, pong.gesture?.id)
        assertEquals(1500L, pong.gesture?.ageMs)
    }

    @Test
    fun `a stale or malformed gesture is ignored`() {
        val stale = JSONObject()
            .put("type", "pong")
            .put("gesture", "release")
            .put("gestureAgeMs", PeerProtocol.GESTURE_WINDOW_MS + 1)
            .toString()
        assertNull(PeerProtocol.parsePong(stale)?.gesture)

        val unknown = JSONObject()
            .put("type", "pong")
            .put("gesture", "pinch")
            .put("gestureAgeMs", 100)
            .toString()
        assertNull(PeerProtocol.parsePong(unknown)?.gesture)

        val negative = JSONObject()
            .put("type", "pong")
            .put("gesture", "grab")
            .put("gestureAgeMs", -1)
            .toString()
        assertNull(PeerProtocol.parsePong(negative)?.gesture)
    }

    @Test
    fun `non-pong and malformed lines yield no pong`() {
        assertNull(PeerProtocol.parsePong("""{"type":"authenticated"}"""))
        assertNull(PeerProtocol.parsePong("garbage"))
        assertNull(PeerProtocol.parsePong(null))
    }

    @Test
    fun `one queue is served to exactly one target`() {
        fun visible(
            fromPhone: Boolean,
            client: Boolean = false,
            peerTarget: Boolean = false,
            macTarget: Boolean = false,
        ) = PeerProtocol.queueVisibleTo(fromPhone, client, peerTarget, macTarget)

        // Nothing picked yet: both a Mac and a phone may pull.
        assertTrue(visible(fromPhone = false))
        assertTrue(visible(fromPhone = true))

        // The user picked a Mac: only the Mac may pull.
        assertTrue(visible(fromPhone = false, macTarget = true))
        assertFalse(visible(fromPhone = true, macTarget = true))

        // A phone listener paired with us: only that phone may pull.
        assertTrue(visible(fromPhone = true, peerTarget = true))
        assertFalse(visible(fromPhone = false, peerTarget = true))

        // This phone is the CLIENT of a phone: we push, so nobody may pull.
        assertFalse(visible(fromPhone = true, client = true))
        assertFalse(visible(fromPhone = false, client = true))
        assertFalse(visible(fromPhone = true, client = true, peerTarget = true))
    }
}
