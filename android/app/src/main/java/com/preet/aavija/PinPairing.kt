package com.preet.aavija

/**
 * Decides whether a Mac is allowed to pair, and enforces the "5 wrong PINs,
 * then lockout" rule. This is the security gate for first contact.
 *
 * This class is deliberately framework-free: it takes a clock ([nowMs]) and a
 * [PairingStore] as inputs instead of touching Android APIs. That lets us unit
 * test every rule (lockout after 5, cooldown expiry, token issuance) on the JVM
 * in milliseconds — no device, no flakiness.
 *
 * ### The trust model, in plain words
 *  - The Mac proves it knows the PIN by returning a proof bound to BOTH the
 *    challenge salt AND the phone's certificate fingerprint. A relay attacker
 *    sees a different fingerprint than the real phone, so its proof won't match
 *    — that's what defeats a man-in-the-middle.
 *  - Once the PIN checks out, we mint a random 256-bit [session token] and hand
 *    it to the Mac. From then on the Mac proves it holds that token on every
 *    future connection. The weak 6-digit PIN is used exactly ONCE; all ongoing
 *    trust rides on the un-brute-forceable token.
 */
class PinPairing(
    private val store: PairingStore,
    private val nowMs: () -> Long,
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 5 * 60 * 1000L // 5-minute cooldown
    }

    /** What the phone says after a Mac submits a pairing proof. */
    sealed class Result {
        /** PIN correct — here's the token to remember me by. */
        data class Paired(val sessionToken: ByteArray) : Result() {
            // ByteArray doesn't implement equals; compare by content.
            override fun equals(other: Any?) =
                other is Paired && other.sessionToken.contentEquals(sessionToken)
            override fun hashCode() = sessionToken.contentHashCode()
        }

        /** Wrong PIN, but you still have attempts left. */
        data object WrongPin : Result()

        /** Too many wrong PINs — refuse to even check until the cooldown ends. */
        data class LockedOut(val remainingMs: Long) : Result()
    }

    /** Are we currently in a lockout window? */
    fun isLockedOut(): Boolean {
        val until = store.lockedUntilMs()
        return until > nowMs()
    }

    fun lockoutRemainingMs(): Long {
        val until = store.lockedUntilMs()
        return (until - nowMs()).coerceAtLeast(0)
    }

    /**
     * Verify a submitted pairing proof against the PIN the user typed into the
     * phone. [phoneFingerprint] is THIS phone's own cert fingerprint — the Mac's
     * proof must have been bound to the same value, so a MITM that showed the
     * Mac a different cert can't produce a matching proof.
     */
    fun submitProof(
        submittedProof: ByteArray,
        salt: ByteArray,
        typedPin: String,
        phoneFingerprint: String,
    ): Result {
        if (isLockedOut()) {
            return Result.LockedOut(lockoutRemainingMs())
        }

        // Recompute what the proof SHOULD be, using the PIN typed on the phone.
        // Binding salt||fingerprint into the HMAC is what makes it MITM-resistant.
        val salted = salt + phoneFingerprint.encodeToByteArray()
        val expected = PairingCrypto.computeProof(typedPin, salted)

        return if (PairingCrypto.verifyProof(expected, submittedProof)) {
            // Success: clear the attempt counter and mint a strong token so the
            // PIN is never needed again for this device.
            store.resetAttempts()
            val token = PairingCrypto.randomToken()
            store.rememberToken(token)
            Result.Paired(token)
        } else {
            recordFailure()
        }
    }

    /** Count one wrong attempt; flip into lockout once the limit is hit. */
    private fun recordFailure(): Result {
        val attempts = store.incrementAttempts()
        if (attempts >= MAX_ATTEMPTS) {
            store.setLockedUntil(nowMs() + LOCKOUT_MS)
            store.resetAttempts() // fresh counter for after the cooldown
            return Result.LockedOut(LOCKOUT_MS)
        }
        // Still have attempts left. The UI can read store.attempts() to show
        // "2 tries left"; here we just report the wrong-PIN outcome.
        return Result.WrongPin
    }

    /**
     * Does this Mac already hold a token we issued? Used to accept a returning,
     * already-paired device without asking for the PIN again.
     */
    fun isPairedToken(candidate: ByteArray): Boolean =
        store.knownTokens().any { it.contentEquals(candidate) }
}

/**
 * Where pairing state lives. A real implementation backs this with Android
 * SharedPreferences; tests use [InMemoryPairingStore].
 */
interface PairingStore {
    fun attempts(): Int
    fun incrementAttempts(): Int
    fun resetAttempts()
    fun lockedUntilMs(): Long
    fun setLockedUntil(ms: Long)
    fun rememberToken(token: ByteArray)
    fun knownTokens(): List<ByteArray>
}

/** Pure in-memory store for unit tests — lets us simulate time passing. */
class InMemoryPairingStore : PairingStore {
    private var _attempts = 0
    private var _lockedUntil = 0L
    private val _tokens = mutableListOf<ByteArray>()

    override fun attempts() = _attempts
    override fun incrementAttempts(): Int = ++_attempts
    override fun resetAttempts() { _attempts = 0 }
    override fun lockedUntilMs() = _lockedUntil
    override fun setLockedUntil(ms: Long) { _lockedUntil = ms }
    override fun rememberToken(token: ByteArray) { _tokens.add(token) }
    override fun knownTokens(): List<ByteArray> = _tokens.toList()
}
