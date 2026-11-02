package com.aartiq.aavija

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Phase 4a: the safety rules for receiving a file. These are the things that
 * must NEVER be got wrong (path tricks, size cap, hash check), so they're
 * pinned down on the JVM rather than found by aiming a rogue peer at a phone.
 */
class FileTransferTest {

    private val goodSha = "ab".repeat(32) // 64 hex chars

    private fun header(
        filename: String = "photo.jpg",
        size: Long = 123,
        sha: String = goodSha,
        type: String = "file",
    ) = JSONObject()
        .put("type", type)
        .put("filename", filename)
        .put("size", size)
        .put("sha256", sha)
        .toString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---- Filename safety ---------------------------------------------------

    @Test
    fun `an ordinary filename is accepted`() {
        assertEquals("photo.jpg", FileTransfer.safeName("photo.jpg"))
    }

    @Test
    fun `path tricks are refused`() {
        assertNull("a forward slash must be refused", FileTransfer.safeName("../etc/passwd"))
        assertNull("a subfolder must be refused", FileTransfer.safeName("a/b"))
        assertNull("a backslash must be refused", FileTransfer.safeName("a\\b"))
        assertNull("dot-dot must be refused", FileTransfer.safeName(".."))
        assertNull("an empty name must be refused", FileTransfer.safeName(""))
    }

    // ---- Header parsing ----------------------------------------------------

    @Test
    fun `a valid header parses`() {
        val result = FileTransfer.parseHeader(header())
        assertTrue("should parse, was $result", result is FileTransfer.HeaderResult.Ok)
        val h = (result as FileTransfer.HeaderResult.Ok).header
        assertEquals("photo.jpg", h.filename)
        assertEquals(123L, h.size)
        assertEquals(goodSha, h.sha256Hex)
    }

    @Test
    fun `a header for another message type is refused`() {
        val result = FileTransfer.parseHeader(header(type = "pair"))
        assertTrue(result is FileTransfer.HeaderResult.Bad)
    }

    @Test
    fun `malformed json is refused`() {
        assertTrue(FileTransfer.parseHeader("not json") is FileTransfer.HeaderResult.Bad)
    }

    @Test
    fun `an unsafe filename in a header is refused`() {
        val result = FileTransfer.parseHeader(header(filename = "../../secret"))
        assertTrue(result is FileTransfer.HeaderResult.Bad)
    }

    @Test
    fun `a file over the cap is refused, but the cap itself is allowed`() {
        assertTrue(
            FileTransfer.parseHeader(header(size = FileTransfer.MAX_FILE_BYTES))
                is FileTransfer.HeaderResult.Ok,
        )
        assertTrue(
            FileTransfer.parseHeader(header(size = FileTransfer.MAX_FILE_BYTES + 1))
                is FileTransfer.HeaderResult.Bad,
        )
    }

    @Test
    fun `a bad sha256 is refused`() {
        assertTrue(FileTransfer.parseHeader(header(sha = "abc")) is FileTransfer.HeaderResult.Bad)
        assertTrue(
            FileTransfer.parseHeader(header(sha = "zz".repeat(32)))
                is FileTransfer.HeaderResult.Bad,
        )
    }

    @Test
    fun `a missing filename is refused`() {
        val noName = JSONObject().put("type", "file").put("size", 1).put("sha256", goodSha).toString()
        assertTrue(FileTransfer.parseHeader(noName) is FileTransfer.HeaderResult.Bad)
    }

    // ---- Streaming / hashing ----------------------------------------------

    @Test
    fun `receiving copies every byte and reports the right hash`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() } // spans several reads
        val out = ByteArrayOutputStream()

        val got = FileTransfer.receive(ByteArrayInputStream(payload), out, payload.size.toLong())

        assertTrue("all bytes must be written", payload.contentEquals(out.toByteArray()))
        assertEquals(sha256Hex(payload), got)
    }

    @Test
    fun `a short stream (truncated file) throws instead of silently succeeding`() {
        val promised = 1000L
        val only = ByteArray(100)
        assertThrows(IOException::class.java) {
            FileTransfer.receive(ByteArrayInputStream(only), ByteArrayOutputStream(), promised)
        }
    }

    @Test
    fun `the header line and the file body share one stream without tearing`() {
        // This is the subtle one: if the header were read through a BufferedReader
        // it would swallow the first bytes of the body. readLine must stop
        // exactly at the newline so the payload survives.
        val body = "BINARY\u0000\u0001DATA".toByteArray()
        val wire = ("{header}\n").toByteArray() + body
        val input = ByteArrayInputStream(wire)

        val line = FileTransfer.readLine(input)
        val out = ByteArrayOutputStream()
        FileTransfer.receive(input, out, body.size.toLong())

        assertEquals("{header}", line)
        assertTrue("the body must be intact after the line read", body.contentEquals(out.toByteArray()))
    }

    @Test
    fun `readLine returns null at end of stream`() {
        assertNull(FileTransfer.readLine(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `writeLine and readLine round-trip`() {
        val out = ByteArrayOutputStream()
        FileTransfer.writeLine(out, """{"type":"auth"}""")
        assertEquals("""{"type":"auth"}""", FileTransfer.readLine(ByteArrayInputStream(out.toByteArray())))
    }
}
