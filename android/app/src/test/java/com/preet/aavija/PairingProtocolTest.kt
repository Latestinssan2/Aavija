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

    /** Build a protocol with a given PIN typed on the phone. */
    private fun proto(typedPin: String) = PairingProtocol(salt, phoneFp, typedPin)

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
}
