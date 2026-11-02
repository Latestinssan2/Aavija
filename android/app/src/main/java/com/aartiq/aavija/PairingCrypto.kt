package com.aartiq.aavija

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pure crypto behind PIN pairing (Phase 3b). No Android imports — so every
 * function here can be unit-tested on the JVM in milliseconds, and the exact
 * same math is mirrored on the Mac in Swift.
 *
 * The one-time PIN handshake works like this (see docs/PROTOCOL.md):
 *
 *   1. Phone sends a random challenge ("salt").
 *   2. Mac derives  key = PBKDF2(pin, salt)   then  proof = HMAC(key, salt).
 *   3. Phone does the same with the PIN the user typed. If proofs match, the
 *      Mac proved it knows the PIN — WITHOUT ever sending the PIN itself.
 *
 * Why this is safe: PBKDF2 "stretches" the weak 6-digit PIN into a strong key
 * by running HMAC hundreds of thousands of times. An eavesdropper who captures
 * the salt + proof still can't recover the PIN cheaply — they'd have to guess
 * all 1,000,000 PINs, each costing a full PBKDF2 run. And because the proof is
 * bound to the TLS certificate fingerprint (checked separately), a relay that
 * swaps in its own certificate is rejected even if it forwards the proof.
 */
object PairingCrypto {

    /**
     * How many times PBKDF2 runs. MUST be identical on the Mac or the two sides
     * derive different keys and pairing always fails. 210,000 is a common,
     * defensible value for modern hardware (OWASP recommends >= 600,000 for
     * SHA-256; we sit in between to stay fast on an older phone).
     */
    const val PBKDF2_ITERATIONS = 210_000

    /** Stretch a PIN + salt into a 256-bit key using PBKDF2-HMAC-SHA256. */
    fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            256, // output key length in bits
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword() // don't leave the PIN sitting in memory
        }
    }

    /** HMAC-SHA256 of [message] under [key]. */
    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** The proof a Mac sends to prove it knows the PIN for a given challenge. */
    fun computeProof(pin: String, salt: ByteArray): ByteArray =
        hmac(deriveKey(pin, salt), salt)

    /**
     * Constant-time proof check. We compare digests in fixed time so an attacker
     * can't measure HOW WRONG a guess was to narrow down the PIN byte-by-byte.
     */
    fun verifyProof(expectedProof: ByteArray, suppliedProof: ByteArray): Boolean =
        MessageDigest.isEqual(expectedProof, suppliedProof)

    /** SHA-256 of raw certificate bytes, lowercase hex — the "fingerprint". */
    fun fingerprint(certBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certBytes)
            .joinToString("") { "%02x".format(it) }

    /** 32 random bytes (a session token). */
    fun randomToken(): ByteArray {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    /** Lowercase hex encoding. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * Decode hex. Throws [IllegalArgumentException] on anything that isn't a
     * valid, even-length hex string — callers use that to REJECT malformed
     * input instead of passing garbage into the crypto.
     */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi != -1 && lo != -1) { "invalid hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
