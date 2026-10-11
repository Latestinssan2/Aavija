package com.preet.aavija

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds Macs on this Wi-Fi so the phone can show them as a list of devices
 * you could pair with (Phase 5a).
 *
 * ### Why the phone has to browse
 * In our swapped architecture the phone is the *listener*: it advertises and
 * waits for a Mac to dial in. That's great for connecting, but it means a phone
 * otherwise has no idea which Macs exist — it only learns about a Mac when one
 * happens to call. To let you *pick* a Mac, the Mac now announces itself with
 * `DNSServiceRegister` (service type [_aavija-mac._tcp][MAC_SERVICE_TYPE]) and
 * this class listens for those announcements.
 *
 * ### The two-step nature of NsdManager
 * `onServiceFound` hands you the *instance name* (the Mac's real machine name)
 * but nothing else. The usual next step is [NsdManager.resolveService], which
 * fills in the address and port — and which is, in practice, the single most
 * failure-prone call in the whole API (it throws if another resolve is in
 * flight, and it silently times out when a record is fresh).
 *
 * We deliberately **don't call it**. Resolving exists to learn *where* to dial,
 * and this phone never dials a Mac — in our swapped architecture the Mac dials
 * us. So the name from `onServiceFound` is all we need, and skipping resolve
 * turns a flaky list into an instant one.
 *
 * This is Android's side of discovery; the Mac's side is `Browser.swift`,
 * which browses for the *phone's* service. The two run independently and do
 * not know about each other.
 */
class MacDiscovery(context: Context) {

    companion object {
        private const val TAG = "AavijaMacDiscovery"
        /** Must match `MacAdvertiser.serviceType` on the Mac exactly. */
        const val MAC_SERVICE_TYPE = "_aavija-mac._tcp"

        /**
         * Is a service type reported by Android the one we're browsing for?
         *
         * Android is inconsistent about the exact string it hands back: some
         * versions return `_aavija-mac._tcp`, others `_aavija-mac._tcp.local.`
         * or a different case. Comparing literally (which is what this used to
         * do) throws away every Mac the moment the string doesn't match
         * character-for-character — so normalise first, then compare.
         */
        fun isOurType(reported: String?): Boolean {
            val normalised = (reported ?: "")
                .trim()
                .trimEnd('.')
                .removeSuffix(".local")
                .trimEnd('.')
                .lowercase()
            return normalised == MAC_SERVICE_TYPE
        }
    }

    /** One Mac the phone can see on this network. */
    data class MacDevice(
        /** Unique per service instance — the announced name, used as our id. */
        val id: String,
        /** Human-readable name, e.g. "Sandipkumar's MacBook Pro". */
        val name: String,
    )

    private val _macs = MutableStateFlow<List<MacDevice>>(emptyList())
    /** Current device list. Empty = nothing found (yet). */
    val macs: StateFlow<List<MacDevice>> = _macs.asStateFlow()

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Start watching for Macs. Safe to call more than once. */
    fun start() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // A Mac announced itself. Android can surface other service
                // types on some networks, so filter — but leniently (see
                // isOurType) rather than with a literal string compare.
                if (!isOurType(info.serviceType)) {
                    Log.d(TAG, "Ignoring service '${info.serviceName}' of type '${info.serviceType}'")
                    return
                }
                addDevice(info.serviceName)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (!isOurType(info.serviceType)) return
                Log.i(TAG, "Mac went away: ${info.serviceName}")
                _macs.value = _macs.value.filterNot { it.id == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Stopped browsing $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "startDiscovery failed: code $errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stopDiscovery failed: code $errorCode")
            }
        }

        discoveryListener = listener
        // PROTOCOL_DNS_SD = 1: the DNS-Service-Discovery protocol (Bonjour).
        nsdManager.discoverServices(MAC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Add (or re-announce) one Mac. Called straight from `onServiceFound`.
     *
     * Android re-sends the announcement every so often, so this runs again for
     * a Mac we already have. We must NOT duplicate the row or reorder the
     * list — the user may be about to tap one, and a list that reshuffles under
     * their finger picks the wrong device. Hence: keep the existing entry
     * untouched if we already have it, and sort by name so order is stable.
     */
    private fun addDevice(name: String?) {
        val id = name ?: return
        val current = _macs.value
        if (current.any { it.id == id }) return // already listed — leave it alone
        Log.i(TAG, "Found Mac: $id")
        _macs.value = (current + MacDevice(id = id, name = id))
            .sortedBy { it.name.lowercase() }
    }

    /** Stop browsing and forget everything found (called on teardown). */
    fun stop() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                // Already stopped — nothing to do.
                Log.d(TAG, "stopServiceDiscovery: ${e.message}")
            }
        }
        discoveryListener = null
        _macs.value = emptyList()
    }
}
