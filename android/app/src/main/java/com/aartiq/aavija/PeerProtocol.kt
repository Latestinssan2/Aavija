package com.aartiq.aavija

import org.json.JSONObject

/**
 * The Android-to-Android side of the wire protocol.
 *
 * A second phone cannot be the listener for the first one (both already run a
 * server), so one phone becomes the *client* for a pairing session. Everything
 * it says is still the exact same newline-delimited JSON that the Mac speaks:
 *
 *   Server → Client   {"type":"challenge","salt":"..."}
 *   Client → Server   {"type":"pair","name":"...","proof":"...","platform":"android","requestId":"..."}
 *   Server → Client   {"type":"paired","token":"..."} | {"type":"rejected",...} | {"type":"locked",...}
 *
 * Returning visits use `auth`, and heartbeats use `ping`/`pong`. That means the
 * phone server needs no second protocol: it already knows how to answer every
 * one of these messages.
 *
 * This object is intentionally Android-free so the wire shapes and the
 * gesture-privacy rule (gestures only ride on a token-authenticated pong) can
 * be unit-tested on the JVM.
 */
object PeerProtocol {

    /** Tells the server this `pair` came from a phone, not a Mac. */
    const val PLATFORM_ANDROID = "android"

    /** The 10-second gesture match window, mirrored from GestureReporter. */
    const val GESTURE_WINDOW_MS = 10_000L

    fun pairMessage(name: String, proofHex: String, requestId: String): String =
        JSONObject()
            .put("type", "pair")
            .put("name", name)
            .put("proof", proofHex)
            .put("platform", PLATFORM_ANDROID)
            .put("requestId", requestId)
            .toString()

    fun authMessage(tokenHex: String): String =
        JSONObject()
            .put("type", "auth")
            .put("token", tokenHex)
            .put("platform", PLATFORM_ANDROID)
            .toString()

    fun pingMessage(tokenHex: String): String =
        JSONObject()
            .put("type", "ping")
            .put("token", tokenHex)
            .put("platform", PLATFORM_ANDROID)
            .toString()

    fun receiveRequest(): String =
        JSONObject()
            .put("type", "receive")
            .put("platform", PLATFORM_ANDROID)
            .toString()

    fun cancelMessage(requestId: String): String =
        JSONObject()
            .put("type", "cancel")
            .put("requestId", requestId)
            .put("platform", PLATFORM_ANDROID)
            .toString()

    /** The random salt the server sent, or null if the line was not a challenge. */
    fun parseChallengeSalt(line: String?): ByteArray? {
        val obj = parse(line) ?: return null
        if (obj.optString("type") != "challenge") return null
        val hex = obj.optString("salt")
        if (hex.isEmpty()) return null
        return try {
            PairingCrypto.fromHex(hex)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The server's answer to `pair` / `auth`. */
    data class Verdict(
        val type: String,
        val tokenHex: String?,
        val reason: String?,
        val retryAfterMs: Long,
    ) {
        val isPaired: Boolean get() = type == "paired"
        val isAuthenticated: Boolean get() = type == "authenticated"
    }

    fun parseVerdict(line: String?): Verdict? {
        val obj = parse(line) ?: return null
        val type = obj.optString("type")
        if (type.isEmpty()) return null
        return Verdict(
            type = type,
            tokenHex = obj.optString("token").ifEmpty { null },
            reason = obj.optString("reason").ifEmpty { null },
            retryAfterMs = obj.optLong("retryAfterMs", 0L),
        )
    }

    /**
     * What a heartbeat told us. Gesture fields are only present when the ping
     * carried a token the server minted, so an unauthenticated caller learns
     * nothing private.
     */
    data class Pong(
        val outgoing: Int,
        val gestureGate: Boolean,
        val gesture: GestureOffer?,
    )

    fun parsePong(line: String?): Pong? {
        val obj = parse(line) ?: return null
        if (obj.optString("type") != "pong") return null

        val gesture = run {
            val kind = obj.optString("gesture")
            if (kind != "grab" && kind != "release") return@run null
            val age = obj.optLong("gestureAgeMs", -1L)
            if (age < 0L || age > GESTURE_WINDOW_MS) return@run null
            GestureOffer(
                type = kind,
                id = obj.optLong("gestureId", 0L),
                ageMs = age,
            )
        }

        return Pong(
            outgoing = obj.optInt("outgoing", 0),
            gestureGate = obj.optBoolean("gestureGate", false),
            gesture = gesture,
        )
    }

    /** True for a phone-originated pairing request (not a Mac's). */
    fun isAndroidPair(line: String?): Boolean {
        val obj = parse(line) ?: return false
        return obj.optString("type") == "pair" &&
            obj.optString("platform") == PLATFORM_ANDROID
    }

    fun requestId(line: String?): String =
        parse(line)?.optString("requestId").orEmpty()

    fun senderName(line: String?): String =
        parse(line)?.optString("name").orEmpty()

    fun isAndroidCancel(line: String?): Boolean {
        val obj = parse(line) ?: return false
        return obj.optString("type") == "cancel" &&
            obj.optString("platform") == PLATFORM_ANDROID
    }

    /** True for a phone-originated token auth (used to keep queue ownership sane). */
    fun isAndroidAuth(line: String?): Boolean {
        val obj = parse(line) ?: return false
        return obj.optString("type") == "auth" &&
            obj.optString("platform") == PLATFORM_ANDROID
    }

    /**
     * May this caller be served the single outgoing file queue?
     *
     * There is only one queue, and the device the user picked last owns it.
     * Both the heartbeat count and the actual `receive` handler use this so a
     * device can never be told "3 files waiting" and then be refused (or be
     * told "0" and still be handed someone else's file).
     */
    fun queueVisibleTo(
        fromPhone: Boolean,
        peerClientActive: Boolean,
        peerTargetActive: Boolean,
        macTargetActive: Boolean,
    ): Boolean = if (fromPhone) {
        // This phone is the CLIENT of a phone peer and pushes to it; the peer
        // must not pull the same file. A Mac the user picked also owns it.
        !peerClientActive && !macTargetActive
    } else {
        // A Mac may pull only when neither phone relationship owns the queue.
        !peerClientActive && !peerTargetActive
    }

    private fun parse(line: String?): JSONObject? {
        if (line.isNullOrBlank()) return null
        return try {
            JSONObject(line)
        } catch (_: Exception) {
            null
        }
    }
}
