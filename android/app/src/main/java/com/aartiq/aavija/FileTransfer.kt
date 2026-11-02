package com.aartiq.aavija

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Phase 4a: the pure parts of receiving a file. Deliberately Android-free so
 * the SAFETY rules — path tricks, size cap, hash check — are unit-tested on the
 * JVM instead of discovered on a device.
 *
 * The wire shape (see docs/PROTOCOL.md §3). Everything rides the SAME TLS
 * tunnel the pairing used, so nobody on the Wi-Fi can read or alter it:
 *
 *   Mac → Phone   {"type":"auth","token":"<hex>"}\n
 *   Phone → Mac   {"type":"authenticated"}\n
 *   Mac → Phone   {"type":"file","filename":"a.jpg","size":1234,"sha256":".."}\n
 *   Mac → Phone   <exactly `size` raw bytes>
 *   Phone → Mac   {"type":"received"}\n   (or {"type":"failed","reason":".."})
 *
 * The receiver hashes the bytes as they arrive and only KEEPS the file if the
 * hash matches the header. On any mismatch it deletes the partial file — a
 * half-written or tampered file must never be left where the user can open it.
 */
object FileTransfer {

    /**
     * Hard cap on one file. A paired Mac is trusted, but storage is precious
     * and an unbounded size is how a bug (or a rogue peer) fills the phone.
     */
    const val MAX_FILE_BYTES: Long = 200L * 1024 * 1024 // 200 MB

    /** The doc promises 1 MB chunks; this is the buffer we read them with. */
    const val CHUNK_BYTES: Int = 1024 * 1024

    /** A validated file header. */
    data class Header(val filename: String, val size: Long, val sha256Hex: String)

    /** What came of reading a header line. */
    sealed class HeaderResult {
        data class Ok(val header: Header) : HeaderResult()
        data class Bad(val reason: String) : HeaderResult()
    }

    /**
     * Turn a raw filename into one that is safe to write into our own folder,
     * or null to refuse it.
     *
     * We REJECT rather than "clean up": a name with a path separator or ".."
     * is a path-traversal attempt, not a typo, and quietly rewriting it would
     * hide the attack. The caller only ever writes into Downloads/GestureShare.
     */
    fun safeName(raw: String): String? {
        if (raw.isEmpty() || raw.length > 255) return null
        if (raw == "." || raw == "..") return null
        if (raw.contains('/') || raw.contains('\\')) return null
        if (raw.any { it.code < 0x20 }) return null // control characters
        return raw
    }

    /** Parse and validate a `{"type":"file",...}` header line. */
    fun parseHeader(line: String): HeaderResult {
        if (line.toByteArray(Charsets.UTF_8).size > PairingProtocol.MAX_LINE_BYTES) {
            return HeaderResult.Bad("line too long")
        }

        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            return HeaderResult.Bad("malformed json")
        }

        if (obj.optString("type") != "file") return HeaderResult.Bad("unexpected type")

        val name = safeName(obj.optString("filename"))
            ?: return HeaderResult.Bad("unsafe filename")

        val size = obj.optLong("size", -1L)
        if (size < 0L) return HeaderResult.Bad("bad size")
        if (size > MAX_FILE_BYTES) return HeaderResult.Bad("file too large")

        val sha = obj.optString("sha256")
        if (sha.length != 64 || sha.any { Character.digit(it, 16) < 0 }) {
            return HeaderResult.Bad("bad sha256")
        }

        return HeaderResult.Ok(Header(name, size, sha.lowercase()))
    }

    /**
     * Copy exactly [size] bytes from [input] to [output] and return the SHA-256
     * (hex) of what we wrote. Throws if the stream ends early — a truncated
     * file is a failure, not a smaller file.
     *
     * Reads what the socket actually has (up to 64 KB at a time) instead of
     * waiting for a whole 1 MB chunk, so a slow sender still makes progress.
     */
    fun receive(input: InputStream, output: OutputStream, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) {
                throw IOException("connection closed after ${size - remaining} of $size bytes")
            }
            output.write(buf, 0, n)
            digest.update(buf, 0, n)
            remaining -= n
        }
        return PairingCrypto.toHex(digest.digest())
    }

    /**
     * Read one '\n'-terminated UTF-8 line, or null at end of stream.
     *
     * Byte-by-byte on purpose: the SAME buffered stream is used for the header
     * line and then for the raw file bytes, so we must not read past the
     * newline into the binary payload. A BufferedReader would.
     */
    fun readLine(input: InputStream, max: Int = PairingProtocol.MAX_LINE_BYTES): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b == '\r'.code) continue // tolerate CRLF
            if (buf.size() >= max) throw IOException("line too long")
            buf.write(b)
        }
    }

    /** Send exactly [size] bytes from [input] to [output]. */
    fun sendBytes(input: InputStream, output: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) throw java.io.IOException("connection closed after ${size - remaining} of $size bytes")
            output.write(buf, 0, n)
            remaining -= n
        }
        output.flush()
    }

    /** Write one UTF-8 line and flush, so the peer sees it immediately. */
    fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
