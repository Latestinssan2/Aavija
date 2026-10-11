package com.preet.aavija

import android.content.Context
import android.content.SharedPreferences

/**
 * [PairingStore] backed by SharedPreferences so pairing state SURVIVES a
 * restart. This matters for security, not just convenience:
 *
 *  - If the lockout lived only in memory, force-quitting the app would reset
 *    the 5-attempt counter — and an attacker could just keep guessing by
 *    swiping the app away. Persisting it closes that hole.
 *  - Session tokens persist so a Mac you paired once stays paired, instead of
 *    forcing a fresh PIN dance on every launch.
 *
 * Tokens are stored as a set of hex strings (SharedPreferences has no byte-set
 * type). Reads/writes are thread-safe — the accept loop reads this off the UI
 * thread while the UI may write the typed PIN.
 */
class PrefsPairingStore(context: Context) : PairingStore {

    private val prefs: SharedPreferences =
        context.applicationContext
            .getSharedPreferences("aavija_pairing", Context.MODE_PRIVATE)

    override fun attempts(): Int = prefs.getInt(KEY_ATTEMPTS, 0)

    override fun incrementAttempts(): Int {
        val next = attempts() + 1
        prefs.edit().putInt(KEY_ATTEMPTS, next).apply()
        return next
    }

    override fun resetAttempts() {
        prefs.edit().putInt(KEY_ATTEMPTS, 0).apply()
    }

    override fun lockedUntilMs(): Long = prefs.getLong(KEY_LOCKED_UNTIL, 0L)

    override fun setLockedUntil(ms: Long) {
        prefs.edit().putLong(KEY_LOCKED_UNTIL, ms).apply()
    }

    override fun rememberToken(token: ByteArray) {
        val hex = PairingCrypto.toHex(token)
        val current = prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_TOKENS, current + hex).apply()
    }

    override fun knownTokens(): List<ByteArray> {
        val set = prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()
        return set.mapNotNull { hex ->
            try {
                PairingCrypto.fromHex(hex)
            } catch (_: Exception) {
                null // skip a corrupt entry rather than crash the app
            }
        }
    }

    companion object {
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_LOCKED_UNTIL = "locked_until_ms"
        private const val KEY_TOKENS = "paired_tokens"
    }
}
