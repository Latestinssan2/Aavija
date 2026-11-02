package com.aartiq.aavija

import android.net.Uri
import java.security.MessageDigest

/**
 * A file the user wants to SEND FROM THIS PHONE TO THE MAC (Phase 4b).
 *
 * We compute its SHA-256 at enqueue time so the header can be sent without
 * re-reading the file later. The URI is stored persistably so we can still
 * read it when the Mac pulls it a few seconds later.
 */
data class OutgoingFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val sha256Hex: String,
)

/**
 * A tiny in-memory queue of files waiting to go to the paired Mac.
 * (Usually 0 or 1 — keeping it simple for now.)
 */
class OutgoingQueue {
    private val queue = mutableListOf<OutgoingFile>()

    fun enqueue(file: OutgoingFile) {
        queue.add(file)
    }

    fun dequeue(): OutgoingFile? {
        return if (queue.isEmpty()) null else queue.removeAt(0)
    }

    /** Look at the next file without taking it — so a failed send can be retried. */
    fun peek(): OutgoingFile? = queue.firstOrNull()

    /** Drop a file only once it has actually been delivered. */
    fun remove(file: OutgoingFile) {
        queue.remove(file)
    }

    fun count(): Int = queue.size

    fun clear() {
        queue.clear()
    }

    companion object {
        /** Compute SHA-256 of the bytes a [streamProvider] can supply. */
        fun sha256(streamProvider: () -> java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            streamProvider().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return PairingCrypto.toHex(digest.digest())
        }
    }
}