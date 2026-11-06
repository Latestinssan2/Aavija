package com.aartiq.aavija

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pairing WIRE PROTOCOL — especially that hostile or malformed input
 * is refused cleanly instead of crashing or reaching the crypto. These run on
 * the JVM against the same code the phone runs.
 */
class PairingProtocolTest {

    private val salt = PairingCrypto.randomToken() // 32 random bytes
    private val phoneFp = "cc".repeat(32)

    /** A store we can inspect / share across protocol instances in one test. */
    private val store = InMemoryPairingStore()

    /**
     * Build a protocol reading [pin] from the "screen" (fixed for tests).
     *
     * Defaults mirror the real app's Phase 5b flow: a device has been picked,
     * and the user has pressed Submit. Tests that care about either override
     * them by name.
     */
    private fun proto(
        pin: String,
        selected: String = "Test Mac",
        submitted: Boolean = true,
    ) = PairingProtocol(
        salt = salt,
        phoneFp = phoneFp,
        store = store,
        selectedMac = { selected },
        pinSubmitted = { submitted },
        typedPin = { pin },
    )

    /** The correct proof a Mac would send for [pin]. */
    private fun proofHex(pin: String): String =
        PairingCrypto.toHex(
            PairingCrypto.computeProof(pin, salt + phoneFp.encodeToByteArray()),
        )

    private fun pairJson(proof: String, name: String = "Test Mac") =
        JSONObject().put("type", "pair").put("proof", proof).put("name", name).toString()

    // ---- Happy path --------------------------------------------------------

    @Test
    fun `challenge line carries the salt`() {
        val line = proto("").challengeLine()
        val obj = JSONObject(line)
        assertEquals("challenge", obj.getString("type"))
        assertEquals(PairingCrypto.toHex(salt), obj.getString("salt"))
    }

    @Test
    fun `correct pin returns paired with a token`() {
        val reply = proto("482913").handle(pairJson(proofHex("482913")))
        val obj = JSONObject(reply)
        assertEquals("paired", obj.getString("type"))
        assertEquals("token should be 64 hex chars (32 bytes)", 64, obj.getString("token").length)
    }

    @Test
    fun `a successful pair remembers which mac it was`() {
        // REGRESSION TEST for "the connection is lost after closing the app":
        // the phone must write down WHICH Mac it paired with, because after a
        // restart there is no live connection left to ask.
        val mac = "Sandipkumar's MacBook Pro"
        proto("482913", selected = mac).handle(
            pairJson(proofHex("482913"), name = mac),
        )
        assertEquals(mac, store.pairedMacName())
    }

    @Test
    fun `a phone pairing does not overwrite the remembered mac name`() {
        // A phone pairing is not a Mac pairing. If it overwrote this, the Mac
        // row would flip to "Tap to pair" even though its token is still valid.
        val mac = "Sandipkumar's MacBook Pro"
        proto("482913", selected = mac).handle(pairJson(proofHex("482913"), name = mac))
        assertEquals(mac, store.pairedMacName())

        val phonePair = JSONObject()
            .put("type", "pair")
            .put("name", "Pixel 8")
            .put("proof", proofHex("482913"))
            .put("platform", PeerProtocol.PLATFORM_ANDROID)
            .put("requestId", "req-7")
            .toString()
        val reply = proto("482913", selected = "Pixel 8").handle(phonePair)

        assertEquals("paired", JSONObject(reply).getString("type"))
        assertEquals(mac, store.pairedMacName())
    }

    @Test
    fun `wrong pin returns rejected`() {
        val reply = proto("482913").handle(pairJson(proofHex("000000")))
        assertEquals("rejected", JSONObject(reply).getString("type"))
    }

    @Test
    fun `proof bound to a different phone cert is rejected`() {
        // MITM defense: the Mac saw a DIFFERENT cert, so its proof is bound to
        // that other fingerprint and must not verify against this phone's.
        val otherFp = "dd".repeat(32)
        val mitmProof = PairingCrypto.toHex(
            PairingCrypto.computeProof("482913", salt + otherFp.encodeToByteArray()),
        )
        val reply = proto("482913").handle(pairJson(mitmProof))
        assertEquals("rejected", JSONObject(reply).getString("type"))
    }

    @Test
    fun `a fresh salt is used per connection so a replayed proof fails`() {
        // Same PIN, same proof bytes — but a DIFFERENT connection's salt. The
        // old proof must not verify (replay protection).
        val firstProof = proofHex("482913")
        val freshSalt = PairingCrypto.randomToken()
        val freshProto = PairingProtocol(
            salt = freshSalt,
            phoneFp = phoneFp,
            store = store,
            selectedMac = { "Test Mac" },
            pinSubmitted = { true },
            typedPin = { "482913" },
        )
        val freshJson = JSONObject()
            .put("type", "pair")
            .put("proof", firstProof)
            .toString()
        assertEquals(
            "replayed proof from another salt must fail",
            "rejected",
            JSONObject(freshProto.handle(freshJson)).getString("type"),
        )
    }

    // ---- Hostile / malformed input is refused, not crashed on --------------

    @Test
    fun `non-json input is rejected`() {
        assertEquals("rejected", JSONObject(proto("482913").handle("DROP TABLE devices")).getString("type"))
    }

    @Test
    fun `empty line is rejected`() {
        assertEquals("rejected", JSONObject(proto("482913").handle("")).getString("type"))
    }

    @Test
    fun `oversized line is rejected without parsing`() {
        val huge = "x".repeat(PairingProtocol.MAX_LINE_BYTES + 1)
        assertEquals("rejected", JSONObject(proto("482913").handle(huge)).getString("type"))
    }

    @Test
    fun `wrong message type is rejected`() {
        val notPair = JSONObject().put("type", "file").put("proof", proofHex("482913")).toString()
        assertEquals("rejected", JSONObject(proto("482913").handle(notPair)).getString("type"))
    }

    @Test
    fun `a ping is answered pong and changes nothing`() {
        // The heartbeat: proves the link is alive without touching pairing state.
        val p = proto("482913")
        val reply = p.handle(JSONObject().put("type", "ping").toString())
        assertEquals("pong", JSONObject(reply).getString("type"))
    }

    @Test
    fun `pong reports the outgoing queue length`() {
        // Phase 4b: the Mac only knows to pull a file because pong says how many
        // are waiting. Zero must still be present — "no field" and "no files"
        // are different things on the wire.
        val p = proto("482913")
        var queued = 0
        p.setOutgoingCountProvider { _ -> queued }
        assertEquals(0, JSONObject(p.handle(JSONObject().put("type", "ping").toString())).getInt("outgoing"))
        queued = 3
        assertEquals(3, JSONObject(p.handle(JSONObject().put("type", "ping").toString())).getInt("outgoing"))
        // A ping still never counts as a PIN attempt.
        assertEquals("paired", JSONObject(p.handle(pairJson(proofHex("482913")))).getString("type"))
    }

    @Test
    fun `pong reports zero to a phone when the queue belongs to a Mac`() {
        // The single queue belongs to whichever device the user picked last. A
        // phone peer must not pull a file that was queued for the Mac, so the
        // listener answers a phone heartbeat with 0 while a Mac still sees it.
        val p = proto("482913")
        p.setOutgoingCountProvider { fromPhone -> if (fromPhone) 0 else 3 }

        fun ping(vararg extra: Pair<String, Any>): Int {
            val obj = JSONObject().put("type", "ping")
            extra.forEach { (key, value) -> obj.put(key, value) }
            return JSONObject(p.handle(obj.toString())).getInt("outgoing")
        }

        assertEquals(3, ping())
        assertEquals(3, ping("token" to "whatever"))
        assertEquals(0, ping("platform" to PeerProtocol.PLATFORM_ANDROID))
        assertEquals(
            0,
            ping(
                "platform" to PeerProtocol.PLATFORM_ANDROID,
                "token" to "whatever",
            ),
        )
    }

    @Test
    fun `pong reports the gesture gate and latest gesture only to the paired Mac`() {
        // Phase 7: the Mac needs two things from the phone — whether Gesture
        // mode is on (so it must not auto-pull) and the phone's latest gesture.
        // Gestures are private, so an unauthenticated heartbeat gets neither.
        val p = proto("482913")
        var gate = true
        var offer: GestureOffer? = GestureOffer(type = "grab", id = 7, ageMs = 123)
        p.setGestureGateProvider { gate }
        p.setGestureOfferProvider { offer }

        val anonymous = JSONObject(p.handle(JSONObject().put("type", "ping").toString()))
        assertEquals(false, anonymous.has("gestureGate"))
        assertEquals(false, anonymous.has("gesture"))

        // Pair to get a real token, then heartbeat with it.
        val token = JSONObject(p.handle(pairJson(proofHex("482913")))).getString("token")
        fun trustedPing() = JSONObject()
            .put("type", "ping")
            .put("token", token)
            .toString()

        val active = JSONObject(p.handle(trustedPing()))
        assertEquals(true, active.getBoolean("gestureGate"))
        assertEquals("grab", active.getString("gesture"))
        assertEquals(7L, active.getLong("gestureId"))
        assertEquals(123L, active.getLong("gestureAgeMs"))

        // A revoked/garbage token is treated like no token at all.
        val forged = JSONObject()
            .put("type", "ping")
            .put("token", "ab".repeat(32))
            .toString()
        val rejected = JSONObject(p.handle(forged))
        assertEquals("pong", rejected.getString("type"))
        assertEquals(false, rejected.has("gesture"))
    }

    @Test
    fun `a ping burns no pin attempt`() {
        val p = proto("482913")
        repeat(20) { p.handle(JSONObject().put("type", "ping").toString()) }
        // Still able to pair with the right PIN afterwards.
        val paired = JSONObject(p.handle(pairJson(proofHex("482913")))).getString("type")
        assertEquals("paired", paired)
    }

    @Test
    fun `missing proof field is rejected`() {
        val noProof = JSONObject().put("type", "pair").put("name", "Mac").toString()
        assertEquals("rejected", JSONObject(proto("482913").handle(noProof)).getString("type"))
    }

    @Test
    fun `proof that is not hex is rejected`() {
        assertEquals("rejected", JSONObject(proto("482913").handle(pairJson("zzzz"))).getString("type"))
    }

    @Test
    fun `proof of wrong byte length is rejected`() {
        // 16 bytes = 32 hex chars — valid hex, but not a 32-byte proof.
        assertEquals("rejected", JSONObject(proto("482913").handle(pairJson("ab".repeat(16)))).getString("type"))
    }

    @Test
    fun `no pin typed on phone is rejected before crypto`() {
        // A valid proof, but nobody has entered a PIN on the phone yet.
        val reply = proto("").handle(pairJson(proofHex("482913")))
        val obj = JSONObject(reply)
        assertEquals("rejected", obj.getString("type"))
        assertTrue("should say no pin entered", obj.getString("reason").contains("pin"))
    }

    @Test
    fun `non-digit pin on phone is rejected`() {
        val reply = proto("abcdef").handle(pairJson(proofHex("482913")))
        assertEquals("rejected", JSONObject(reply).getString("type"))
    }

    // ---- Returning Mac (token auth) ----------------------------------------

    @Test
    fun `a minted token authenticates without the pin`() {
        // Pair once to obtain a token.
        val paired = JSONObject(proto("482913").handle(pairJson(proofHex("482913"))))
        assertEquals("paired", paired.getString("type"))
        val token = paired.getString("token")

        // Now a NEW connection (fresh protocol, empty PIN on screen) authenticates.
        val authJson = JSONObject().put("type", "auth").put("token", token).toString()
        val freshSaltProto = PairingProtocol(
            salt = PairingCrypto.randomToken(),
            phoneFp = phoneFp,
            store = store,
            typedPin = { "" },
        )
        assertEquals(
            "stored token must be accepted",
            "authenticated",
            JSONObject(freshSaltProto.handle(authJson)).getString("type"),
        )
    }

    @Test
    fun `an unknown token is rejected`() {
        val authJson = JSONObject().put("type", "auth").put("token", "ab".repeat(32)).toString()
        assertEquals("rejected", JSONObject(proto("").handle(authJson)).getString("type"))
    }

    @Test
    fun `malformed token hex is rejected`() {
        val authJson = JSONObject().put("type", "auth").put("token", "zz").toString()
        assertEquals("rejected", JSONObject(proto("").handle(authJson)).getString("type"))
    }

    // ---- Phase 5b: picking a device, and Submit-gated verification --------

    private fun pollJson(name: String) =
        JSONObject().put("type", "poll").put("name", name).toString()

    @Test
    fun `a poll from the selected mac is told to show a pin`() {
        val reply = proto("").handle(pollJson("Test Mac"))
        val obj = JSONObject(reply)
        assertEquals("poll", obj.getString("type"))
        assertEquals("pair", obj.getString("action"))
    }

    @Test
    fun `a poll from any other mac is told to wait`() {
        val reply = proto("", selected = "Test Mac").handle(pollJson("Rogue Mac"))
        assertEquals("wait", JSONObject(reply).getString("action"))
    }

    @Test
    fun `a poll is refused while nothing is selected`() {
        val reply = proto("", selected = "").handle(pollJson("Test Mac"))
        assertEquals("wait", JSONObject(reply).getString("action"))
    }

    @Test
    fun `a poll never burns a pin attempt`() {
        val before = store.attempts()
        proto("").handle(pollJson("Test Mac"))
        assertEquals("polling must be free", before, store.attempts())
    }

    @Test
    fun `a mac the user did not pick cannot pair`() {
        val reply = proto("482913", selected = "Test Mac")
            .handle(pairJson(proofHex("482913"), name = "Rogue Mac"))
        val obj = JSONObject(reply)
        assertEquals("rejected", obj.getString("type"))
        assertEquals("not the selected device", obj.getString("reason"))
        assertEquals("must not burn an attempt", 0, store.attempts())
    }

    @Test
    fun `selection is not enforced when nothing is picked`() {
        // Keeps the manual "Pair" button on the Mac working: with no device
        // chosen on the phone, any Mac may still pair.
        val reply = proto("482913", selected = "").handle(pairJson(proofHex("482913")))
        assertEquals("paired", JSONObject(reply).getString("type"))
    }

    @Test
    fun `a pin is not checked until submit is pressed`() {
        // The PIN is fully typed AND correct — but the user hasn't submitted.
        val reply = proto("482913", submitted = false).handle(pairJson(proofHex("482913")))
        val obj = JSONObject(reply)
        assertEquals("rejected", obj.getString("type"))
        assertEquals("no pin entered", obj.getString("reason"))
        assertEquals("must not burn an attempt", 0, store.attempts())
    }

    @Test
    fun `one submit costs exactly one attempt`() {
        // REGRESSION TEST for "rejected: too many attempts" appearing before
        // the user pressed anything. The Mac reconnects every ~1.5s; only the
        // connections where Submit is armed may consume one of the five tries.
        val armed = proto("000000", submitted = true)
        assertEquals("rejected", JSONObject(armed.handle(pairJson(proofHex("482913")))).getString("type"))
        assertEquals(1, store.attempts())

        val disarmed = proto("000000", submitted = false)
        repeat(4) { disarmed.handle(pairJson(proofHex("482913"))) }
        assertEquals("the Mac's retries must stay free", 1, store.attempts())
    }

    @Test
    fun `attempts remaining counts down and the lockout follows`() {
        val pairing = PinPairing(store) { 0L }
        assertEquals(PinPairing.MAX_ATTEMPTS, pairing.attemptsRemaining())

        val attempt = proto("000000")
        repeat(4) { attempt.handle(pairJson(proofHex("482913"))) }
        assertEquals(1, pairing.attemptsRemaining())
        assertEquals("not locked yet", false, pairing.isLockedOut())

        val last = JSONObject(attempt.handle(pairJson(proofHex("482913"))))
        assertEquals("locked", last.getString("type"))
        assertEquals("locked out means zero left", 0, pairing.attemptsRemaining())
        assertEquals(true, pairing.isLockedOut())
    }

    @Test
    fun `a lockout survives a brand new protocol instance`() {
        // The store is shared, so a FRESH connection still sees the lockout.
        // This is why force-quitting the app cannot reset the counter.
        val attempt = proto("000000")
        repeat(PinPairing.MAX_ATTEMPTS) { attempt.handle(pairJson(proofHex("482913"))) }

        val fresh = proto("482913")
        assertEquals(
            "a relaunch must not clear the lockout",
            "locked",
            JSONObject(fresh.handle(pairJson(proofHex("482913")))).getString("type"),
        )
    }
}
