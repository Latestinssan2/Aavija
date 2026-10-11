package com.preet.aavija

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
