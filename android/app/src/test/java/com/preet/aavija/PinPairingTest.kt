package com.preet.aavija

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pairing GATE — the rules that decide whether a Mac is allowed
 * to pair. These are the "5 wrong PINs then lockout" requirements, so they're
 * tested on the JVM where a logic bug costs seconds to find, not a frustrating
 * on-device session.
 */
class PinPairingTest {

    private val salt = "0123456789abcdef0123456789abcdef".encodeToByteArray()
    private val phoneFp = "aa".repeat(32) // any 64-char fingerprint

    /** A fake clock we can advance to simulate the lockout cooldown passing. */
    private class Clock(var now: Long = 1_000_000L)

    /** Compute the proof a Mac would send for a given PIN. */
    private fun proofFor(pin: String): ByteArray =
        PairingCrypto.computeProof(pin, salt + phoneFp.encodeToByteArray())

    @Test
    fun `correct pin pairs and returns a 32-byte token`() {
        val clock = Clock()
        val pairing = PinPairing(InMemoryPairingStore()) { clock.now }

        val result = pairing.submitProof(proofFor("482913"), salt, "482913", phoneFp)

        assertTrue("should be Paired, was $result", result is PinPairing.Result.Paired)
        assertEquals(32, (result as PinPairing.Result.Paired).sessionToken.size)
    }

    @Test
    fun `wrong pin is rejected but does not lock out immediately`() {
        val clock = Clock()
        val pairing = PinPairing(InMemoryPairingStore()) { clock.now }

        val result = pairing.submitProof(proofFor("482913"), salt, "000000", phoneFp)

        assertEquals(PinPairing.Result.WrongPin, result)
        assertFalse("1 wrong try must not lock out", pairing.isLockedOut())
    }

    @Test
    fun `five wrong pins trigger lockout`() {
        val clock = Clock()
        val store = InMemoryPairingStore()
        val pairing = PinPairing(store) { clock.now }

        // 4 wrong tries: still allowed.
        repeat(4) {
            pairing.submitProof(proofFor("482913"), salt, "000000", phoneFp)
            assertFalse("must not lock before the 5th", pairing.isLockedOut())
        }

        // 5th wrong try: locked out.
        val fifth = pairing.submitProof(proofFor("482913"), salt, "000000", phoneFp)
        assertTrue("5th wrong try should lock out", fifth is PinPairing.Result.LockedOut)
        assertTrue("should now be locked", pairing.isLockedOut())
    }

    @Test
    fun `during lockout even the correct pin is refused`() {
        val clock = Clock()
        val pairing = PinPairing(InMemoryPairingStore()) { clock.now }

        repeat(5) { pairing.submitProof(proofFor("482913"), salt, "000000", phoneFp) }
        assertTrue(pairing.isLockedOut())

        // Someone now types the RIGHT pin while locked — must still be refused.
        val result = pairing.submitProof(proofFor("482913"), salt, "482913", phoneFp)
        assertTrue("correct pin must not work during lockout", result is PinPairing.Result.LockedOut)
    }

    @Test
    fun `lockout expires after the cooldown and counter restarts`() {
        val clock = Clock()
        val pairing = PinPairing(InMemoryPairingStore()) { clock.now }

        repeat(5) { pairing.submitProof(proofFor("482913"), salt, "000000", phoneFp) }
        assertTrue("should start locked", pairing.isLockedOut())

        // Simulate the 5-minute cooldown elapsing.
        clock.now += PinPairing.LOCKOUT_MS + 1
        assertFalse("lockout must expire", pairing.isLockedOut())

        // And a correct pin now works again (counter was reset, not stuck).
        val result = pairing.submitProof(proofFor("482913"), salt, "482913", phoneFp)
        assertTrue("correct pin should work after cooldown", result is PinPairing.Result.Paired)
    }

    @Test
    fun `proof for a different fingerprint is rejected`() {
        // This is the MITM defense: the Mac's proof is bound to the cert it saw.
        // If it saw a DIFFERENT cert than this phone's, the proof won't match.
        val clock = Clock()
        val pairing = PinPairing(InMemoryPairingStore()) { clock.now }

        val otherFp = "bb".repeat(32)
        val proofBoundToOtherCert = PairingCrypto.computeProof(
            "482913",
            salt + otherFp.encodeToByteArray(),
        )

        val result = pairing.submitProof(proofBoundToOtherCert, salt, "482913", phoneFp)
        assertEquals("proof bound to the wrong cert must fail", PinPairing.Result.WrongPin, result)
    }

    @Test
    fun `a remembered token is accepted without the pin`() {
        val clock = Clock()
        val store = InMemoryPairingStore()
        val pairing = PinPairing(store) { clock.now }

        // Pair once to get a token.
        val paired = pairing.submitProof(proofFor("482913"), salt, "482913", phoneFp)
        val token = (paired as PinPairing.Result.Paired).sessionToken

        assertTrue("stored token must be recognized", pairing.isPairedToken(token))
        assertFalse("a random token must be rejected", pairing.isPairedToken(ByteArray(32)))
    }
}
