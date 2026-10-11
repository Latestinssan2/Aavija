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
 * A Mac that already paired comes back with an `auth` message instead, proving
 * it still holds the token that was minted for it — no PIN needed again.
 *
 * Everything here is deliberately free of Android imports so the whole
 * decision tree is unit-testable on the JVM.
 */

/**
 * Turns raw lines from the wire into reply lines. One instance is created per
 * connection (so each connection gets a FRESH salt — a captured proof is
 * useless against a new challenge, which is our replay protection).
 *
 * @param salt      this connection's challenge (random bytes).
 * @param phoneFp   THIS phone's own cert fingerprint.
 * @param store     where attempts / lockout / known tokens live. Injected so
 *                  the real app can persist it (a restart must NOT clear a
 *                  lockout) while tests use an in-memory one.
 * @param typedPin  reads whatever PIN is CURRENTLY on the phone screen. Called
 *                  at verification time, not construction, so the user can
 *                  type it while a Mac is waiting.
 */
class PairingProtocol(
    private val salt: ByteArray,
    private val phoneFp: String,
    private val store: PairingStore,
    private val typedPin: () -> String,
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

        // 3) It must be a message type we speak at this step.
        return when (obj.optString("type")) {
            "pair" -> handlePair(obj)
            "auth" -> handleAuth(obj)
            else -> reject("unexpected type")
        }
    }

    /** First contact: verify the Mac knows the PIN. */
    private fun handlePair(obj: JSONObject): String {
        // Required fields must exist and be the right shape.
        val proofHex = obj.optString("proof")
        if (proofHex.isEmpty()) return reject("missing proof")

        // The proof must be valid hex of the right length (32 bytes = 64 hex).
        val proof = try {
            PairingCrypto.fromHex(proofHex)
        } catch (_: IllegalArgumentException) {
            return reject("proof not hex")
        }
        if (proof.size != 32) return reject("proof wrong length")

        // A PIN must actually be typed on the phone before we can check.
        // (The user types the PIN the Mac is showing.)
        val pin = typedPin()
        if (pin.length != 6 || pin.any { it !in '0'..'9' }) {
            return reject("no pin entered")
        }

        // All good — hand it to the security gate.
        val pairing = PinPairing(store) { System.currentTimeMillis() }
        return when (val result = pairing.submitProof(proof, salt, pin, phoneFp)) {
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

    /** Returning visit: the Mac proves it still holds a token we minted. */
    private fun handleAuth(obj: JSONObject): String {
        val tokenHex = obj.optString("token")
        if (tokenHex.isEmpty()) return reject("missing token")
        val token = try {
            PairingCrypto.fromHex(tokenHex)
        } catch (_: IllegalArgumentException) {
            return reject("token not hex")
        }

        val pairing = PinPairing(store) { System.currentTimeMillis() }
        return if (pairing.isPairedToken(token)) {
            JSONObject().put("type", "authenticated").toString()
        } else {
            reject("unknown token")
        }
    }

    private fun reject(reason: String): String =
        JSONObject().put("type", "rejected").put("reason", reason).toString()
}
