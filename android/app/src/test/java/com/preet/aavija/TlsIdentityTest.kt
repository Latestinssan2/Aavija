package com.preet.aavija

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Proves the phone's TLS server can actually mint a certificate and complete
 * a handshake — the riskiest, most platform-specific piece of Phase 3a.
 *
 * This runs on the JVM (not the phone) because TlsIdentity uses only plain
 * Java + BouncyCastle, so we can verify it in seconds instead of installing
 * an APK. If this passes, the cert plumbing works; the phone test then only
 * has to confirm the Mac can reach it over real Wi-Fi.
 */
class TlsIdentityTest {

    /** A client that skips cert validation — we're testing the SERVER here. */
    private fun trustAllClientContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return ctx
    }

    @Test
    fun `server socket is created on a real port`() {
        val server = TlsIdentity.createSslServerSocket()
        try {
            assertTrue("OS should assign a free port", server.localPort > 0)
            assertTrue("should be a TLS server socket", server.useClientMode == false)
        } finally {
            server.close()
        }
    }

    @Test
    fun `tls handshake and a line of text round-trip through the server`() {
        val server = TlsIdentity.createSslServerSocket()
        val port = server.localPort

        // Server side: accept one connection, do the handshake (implicit on
        // first read), and read a single line — exactly what the real accept
        // loop in BonjourAdvertiser does.
        val received = StringBuilder()
        val serverThread = Thread {
            server.use { srv ->
                val conn = srv.accept() as SSLSocket
                conn.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val line = reader.readLine()
                    received.append(line)
                }
            }
        }
        serverThread.start()

        // Client side: connect over TLS and send a terminated line.
        trustAllClientContext().socketFactory.createSocket("127.0.0.1", port).use { raw ->
            val client = raw as SSLSocket
            client.use { c ->
                // Trigger the handshake explicitly so a failure surfaces here.
                c.startHandshake()
                c.getOutputStream().write("hello over tls\n".toByteArray())
                c.getOutputStream().flush()
            }
        }

        serverThread.join(10_000)

        assertEquals(
            "phone should read exactly the line the Mac sent",
            "hello over tls",
            received.toString(),
        )
    }

    @Test
    fun `a plaintext client cannot talk to the tls server`() {
        // This is the "never silently downgrade" guarantee: if someone tries a
        // bare (unencrypted) connection, the TLS server must refuse to deliver
        // that data to the app rather than falling back to plaintext.
        //
        // Note: the server may reply with a short TLS *alert record* (an error
        // notice like "unexpected message") — that's correct TLS behavior. What
        // must NOT happen is the plaintext line reaching application code.
        val server = TlsIdentity.createSslServerSocket()
        val port = server.localPort

        // Server side: record whatever the application layer actually reads.
        val serverSaw = StringBuilder()
        val handshakeFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        val serverThread = Thread {
            server.use { srv ->
                try {
                    val conn = srv.accept()
                    conn.use { c ->
                        val line = c.getInputStream().bufferedReader().readLine()
                        if (line != null) serverSaw.append(line)
                    }
                } catch (_: Exception) {
                    // Expected: a non-TLS client cannot complete the handshake.
                    handshakeFailed.set(true)
                }
            }
        }
        serverThread.start()

        // A raw TCP client that speaks no TLS at all.
        java.net.Socket("127.0.0.1", port).use { raw ->
            raw.getOutputStream().write("not tls at all\n".toByteArray())
            raw.getOutputStream().flush()
            raw.getInputStream().readBytes() // may be a TLS alert; ignore content
        }

        serverThread.join(10_000)

        assertTrue(
            "TLS handshake should fail for a plaintext client",
            handshakeFailed.get(),
        )
        assertEquals(
            "plaintext line must never reach the application layer",
            "",
            serverSaw.toString(),
        )
    }

    @Test
    fun `the certificate presented on the wire is the one we fingerprint`() {
        // THE regression test for pairing. The Mac binds its PIN proof to the
        // fingerprint of the cert it saw during the handshake, and the phone
        // verifies that proof against TlsIdentity.fingerprint(). Those two
        // MUST describe the same certificate. When they didn't (a fresh random
        // cert was minted for each), every PIN — even the right one — was
        // rejected as "wrong".
        val server = TlsIdentity.createSslServerSocket()
        val port = server.localPort

        val presented = java.util.concurrent.atomic.AtomicReference<X509Certificate>()
        val serverThread = Thread {
            server.use { srv ->
                srv.accept().use { conn -> (conn as SSLSocket).startHandshake() }
            }
        }
        serverThread.start()

        trustAllClientContext().socketFactory.createSocket("127.0.0.1", port).use { raw ->
            val client = raw as SSLSocket
            client.use { c ->
                c.startHandshake()
                presented.set(c.session.peerCertificates[0] as X509Certificate)
            }
        }
        serverThread.join(10_000)

        assertEquals(
            "the cert the phone PRESENTS must be the cert we hash for pairing",
            TlsIdentity.fingerprint(),
            PairingCrypto.fingerprint(presented.get().encoded),
        )
    }
}
