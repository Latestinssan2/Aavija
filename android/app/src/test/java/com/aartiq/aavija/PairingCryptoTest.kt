package com.aartiq.aavija

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the PIN-pairing math is correct and tamper-resistant BEFORE we ever
 * try to pair two real devices. These run on the JVM, so a bug here costs
 * seconds to find — not a frustrating on-device debugging session.
 */
class PairingCryptoTest {

    private val salt = "0123456789abcdef0123456789abcdef".encodeToByteArray()

    @Test
    fun `same pin and salt always produce the same proof`() {
        val a = PairingCrypto.computeProof("482913", salt)
        val b = PairingCrypto.computeProof("482913", salt)
        assertArrayEquals("deterministic: same inputs -> same proof", a, b)
    }

    @Test
    fun `a different pin produces a different proof`() {
        val correct = PairingCrypto.computeProof("482913", salt)
        val wrong = PairingCrypto.computeProof("482914", salt)
        assertFalse(
            "a one-digit-off PIN must NOT verify",
            PairingCrypto.verifyProof(correct, wrong),
        )
    }

    @Test
    fun `a different salt produces a different proof`() {
        // This is what stops replay: a captured proof is useless against a new
        // challenge, because the proof is bound to the salt.
        val otherSalt = "fedcba9876543210fedcba9876543210".encodeToByteArray()
        val captured = PairingCrypto.computeProof("482913", salt)
        val replayed = PairingCrypto.computeProof("482913", otherSalt)
        assertNotEquals(captured.toList(), replayed.toList())
        assertFalse(
            "a proof for one challenge must not verify against another",
            PairingCrypto.verifyProof(captured, replayed),
        )
    }

    @Test
    fun `proof never contains the raw pin`() {
        // The PIN itself must not appear in the proof — only the one-way HMAC.
        // (If it did, an eavesdropper could just read it straight off the wire.)
        val pin = "482913"
        val proof = PairingCrypto.computeProof(pin, salt).toList()
        val pinBytes = pin.encodeToByteArray().toList()
        // Check the PIN bytes don't show up as a contiguous run in the proof.
        assertFalse(
            "raw PIN leaked into the proof",
            proof.windowed(pinBytes.size).contains(pinBytes),
        )
    }

    @Test
    fun `fingerprints are stable and distinct`() {
        val certA = "pretend-this-is-cert-A".encodeToByteArray()
        val certB = "pretend-this-is-cert-B".encodeToByteArray()

        val fpA1 = PairingCrypto.fingerprint(certA)
        val fpA2 = PairingCrypto.fingerprint(certA)
        val fpB = PairingCrypto.fingerprint(certB)

        assertEquals("same cert -> same fingerprint", fpA1, fpA2)
        assertNotEquals("different certs -> different fingerprints", fpA1, fpB)
        assertEquals("SHA-256 hex is 64 chars", 64, fpA1.length)
    }

    @Test
    fun `session tokens are random and distinct`() {
        val t1 = PairingCrypto.randomToken()
        val t2 = PairingCrypto.randomToken()
        assertEquals("token is 32 bytes", 32, t1.size)
        assertNotEquals(
            "two tokens must differ (else they're not random)",
            t1.toList(),
            t2.toList(),
        )
    }
}
