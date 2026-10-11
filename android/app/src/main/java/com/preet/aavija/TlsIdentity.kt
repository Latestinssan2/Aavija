package com.preet.aavija

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Creates the phone's TLS identity (Phase 3a).
 *
 * The phone is the TLS *server*, so it needs a certificate + private key to
 * encrypt with. We mint a **self-signed** certificate on first run:
 *
 *  - "Self-signed" = the certificate vouches for itself instead of being
 *    signed by a public Certificate Authority. Perfect for our use: there's
 *    no public CA for a phone on your living-room Wi-Fi, and we authenticate
 *    the pairing with a PIN + fingerprint pinning instead (Phase 3b).
 *
 * Only the *phone* needs this. The Mac is the TLS client and needs no cert,
 * which sidesteps the painful cert-generation on macOS entirely.
 *
 * We use BouncyCastle purely as an ASN.1 builder (we do NOT register it as a
 * JCA security provider), which avoids the usual Android class conflicts.
 */
object TlsIdentity {

    /**
     * The ONE keypair + certificate this phone uses for its whole run.
     *
     * This is the crux of the whole pairing scheme, so it is spelled out:
     * the certificate the TLS server PRESENTS on the wire and the certificate
     * we HASH into [fingerprint] must be the SAME object. The Mac binds its PIN
     * proof to the fingerprint it saw during the handshake, and we verify that
     * proof against our own [fingerprint]. If the two certs were minted
     * separately — as they used to be — the Mac would bind to one and we'd
     * check against the other, and EVERY PIN (correct or not) would be rejected.
     *
     * Minted lazily on first use, then shared by BOTH [fingerprint] and
     * [createSslServerSocket]. Never regenerate it inside a running app.
     */
    @Volatile
    private var keyStore: KeyStore? = null

    /**
     * This phone's own certificate fingerprint (SHA-256 of the DER cert, hex).
     * The PIN-pairing proof is bound to this value: a Mac must have seen THIS
     * exact cert during the TLS handshake to produce a matching proof, so a
     * man-in-the-middle showing its own cert is rejected.
     */
    @Volatile
    private var cachedFingerprint: String? = null

    /** The shared identity, minted once on first use (thread-safe). */
    private fun identity(): KeyStore {
        keyStore?.let { return it }
        synchronized(this) {
            keyStore?.let { return it }
            return createKeyStore().also { keyStore = it }
        }
    }

    /**
     * This phone's certificate — exactly the one the TLS server presents.
     * Exposed so tests can prove the presented cert IS the fingerprinted one.
     */
    fun certificate(): X509Certificate =
        identity().getCertificate("aavija") as X509Certificate

    fun fingerprint(): String {
        cachedFingerprint?.let { return it }
        val fp = PairingCrypto.fingerprint(certificate().encoded)
        cachedFingerprint = fp
        return fp
    }

    /** Mint a fresh keypair + self-signed cert and load them into a KeyStore. */
    private fun createKeyStore(): KeyStore {
        // 1) A 2048-bit RSA keypair (the math "lock and key").
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        // 2) Wrap the public key in a self-signed X.509 certificate.
        val cert = selfSign(keyPair, "CN=Aavija, O=Aavija")

        // 3) Stash key + cert in an in-memory PKCS#12 KeyStore.
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null) // null = create empty in-memory store
        ks.setKeyEntry("aavija", keyPair.private, charArrayOf(), arrayOf(cert))
        return ks
    }

    /** Build a self-signed X.509 certificate around a keypair. */
    private fun selfSign(
        keyPair: java.security.KeyPair,
        dn: String,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 1000L * 60 * 60 * 24) // valid from yesterday
        val notAfter = Date(now + 1000L * 60 * 60 * 24 * 365 * 10) // 10 years
        val x500 = X500Name(dn)
        val serial = BigInteger(64, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            x500,          // issuer
            serial,
            notBefore,
            notAfter,
            x500,          // subject (same as issuer = self-signed)
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    /**
     * Produce a ready-to-use [SSLServerSocket]. The caller passes port 0 to
     * let the OS pick a free port (same trick as the plain ServerSocket).
     */
    fun createSslServerSocket(): SSLServerSocket {
        // Use the SAME identity we fingerprint — see [identity]. Minting a
        // second cert here is precisely the bug that made every PIN fail.
        val ks = identity()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf()) // empty password: in-memory only
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    }
}
