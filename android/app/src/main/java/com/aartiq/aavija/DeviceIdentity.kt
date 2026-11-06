package com.aartiq.aavija

import android.content.Context

/**
 * A stable random id for THIS install.
 *
 * Two identical phone models advertise the same Bonjour name ("Pixel 7"), so a
 * name alone cannot tell "my own service" from "the other phone". We publish
 * this id as a TXT attribute on the service record and skip the record whose
 * id matches ours. It is random and local: no hardware identifier, nothing
 * leaves the Wi-Fi, and it survives restarts so the filter stays reliable.
 */
object DeviceIdentity {
    private const val PREFS = "aavija_identity"
    private const val KEY_ID = "device_id"

    fun id(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }

        val fresh = PairingCrypto.toHex(PairingCrypto.randomToken()).take(16)
        prefs.edit().putString(KEY_ID, fresh).apply()
        return fresh
    }
}
