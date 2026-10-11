package com.preet.aavija

import org.json.JSONException
import org.json.JSONObject

/**
 * The pairing wire protocol (Phase 3b), spoken over the TLS connection.
 *
 * It's newline-delimited JSON: each message is one line. JSON is easy to read,
 * easy to debug in a log, and easy to test — and since it all rides inside the
 * TLS tunnel from Phase 3a, nobody on the Wi-Fi can read or alter it.
 *
 * ### The handshake, in plain words
 *  1. Mac connects. Phone sends a challenge: a random salt.
 *  2. Mac answers with a `pair` message: a proof it knows the PIN (computed
 *     over the salt + the phone cert fingerprint it saw on the wire) and its
 *     own name.
 *  3. Phone checks the proof against the PIN the user typed, using ITS OWN
 *     cert fingerprint. If the Mac had been talking to an impostor, its proof
 *     would be bound to a different fingerprint and would NOT match — that's
 *     the man-in-the-middle defense.
 *  4. Phone replies `paired` (with a session token), `rejected`, or `locked`.
 *
 * Everything here is deliberately free of Android imports so the whole
 * decision tree is unit-testable on the JVM.
 */

/** What the Mac sends after receiving a challenge. */
data class PairRequest(val proofHex: String, val macName: String)

/** What the phone decides. Maps 1:1 onto a reply line. */
sealed class PairingDecision {
    data class Paired(val tokenHex: String) : PairingDecision()
    data class Rejected(val reason: String) : PairingDecision()
    data class Locked(val retryAfterMs: Long) : PairingDecision()
}

/**
 * Turns raw lines from the wire into [PairingDecision]s. One instance per
 * pairing attempt; the caller supplies the salt up front and the PIN the user
 * typed into the phone.
 *
 * @param salt        the challenge this attempt must answer (hex).
 * @param phoneFp     THIS phone's own cert fingerprint.
 * @param typedPin    the PIN currently typed on the phone screen ("" if none).
 */
class PairingProtocol(
    private val salt: ByteArray,
    private val phoneFp: String,
    private val typedPin: String,
) {
    companion object {
        /** Cap on inbound line size — a hostile client shouldn't be able to
         *  make us buffer an unbounded string. 4 KB is plenty for our messages. */
        const val MAX_LINE_BYTES = 4096
    }

    /** The challenge line the phone sends first. */
    fun challengeLine(): String =
        JSONObject()
            .put("type", "challenge")
            .put("salt", PairingCrypto.toHex(salt))
            .toString()

    /**
     * Process one line from the Mac and return the phone's reply line.
     *
     * Hostile input is rejected here, BEFORE it can reach the crypto: oversized
     * lines, non-JSON, a `type` we don't speak, missing fields, or hex that
     * isn't valid hex. None of those are treated as "wrong PIN" — they're just
     * refused, and they don't burn a lockout attempt.
     */
    fun handle(line: String): String {
        // 1) Size guard.
        if (line.toByteArray().size > MAX_LINE_BYTES) {
            return reject("line too long")
        }

        // 2) It must be a JSON object. Anything else is refused.
        val obj = try {
            JSONObject(line)
        } catch (_: JSONException) {
            return reject("malformed json")
        }

        // 3) It must be the message type we expect at this step.
        if (obj.optString("type") != "pair") {
            return reject("unexpected type")
        }

        // 4) Required fields must exist and be the right shape.
        val proofHex = obj.optString("proof")
        if (proofHex.isEmpty()) return reject("missing proof")
        val macName = obj.optString("name").ifEmpty { "Aavija Mac" }

        // 5) The proof must be valid hex of the right length (32 bytes = 64 hex).
        val proof = try {
            PairingCrypto.fromHex(proofHex)
        } catch (_: IllegalArgumentException) {
            return reject("proof not hex")
        }
        if (proof.size != 32) return reject("proof wrong length")

        // 6) A PIN must actually be typed on the phone before we can check.
        //    (The user types the PIN the Mac is showing.)
        if (typedPin.length != 6 || typedPin.any { it !in '0'..'9' }) {
            return reject("no pin entered")
        }

        // 7) All good — hand it to the security gate.
        val result = PinPairing(
            store = InMemoryPairingStore(), // caller swaps for a persistent one
            nowMs = { System.currentTimeMillis() },
        ).submitProof(proof, salt, typedPin, phoneFp)

        return when (result) {
            is PinPairing.Result.Paired -> JSONObject()
                .put("type", "paired")
                .put("token", PairingCrypto.toHex(result.sessionToken))
                .toString()

            PinPairing.Result.WrongPin -> JSONObject()
                .put("type", "rejected")
                .put("reason", "wrong pin")
                .toString()

            is PinPairing.Result.LockedOut -> JSONObject()
                .put("type", "locked")
                .put("retryAfterMs", result.remainingMs)
                .toString()
        }
    }

    private fun reject(reason: String): String =
        JSONObject().put("type", "rejected").put("reason", reason).toString()
}
