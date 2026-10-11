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
 * Discovery only yields *service names* — no addresses, no details. To get
 * anything useful you must call [resolveService] on each one. Android also
 * forbids resolving the same service twice at once, so we track what's already
 * in flight (and drop repeats) rather than letting it throw.
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
    private var resolveListener: NsdManager.ResolveListener? = null

    /** Names we've already asked to resolve, to avoid duplicate in-flight calls. */
    private val resolving = mutableSetOf<String>()

    /** Start watching for Macs. Safe to call more than once. */
    fun start() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // A Mac announced itself. We only care about our own type —
                // Android can surface other services on some networks.
                if (info.serviceType != MAC_SERVICE_TYPE) return
                requestResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (info.serviceType != MAC_SERVICE_TYPE) return
                Log.i(TAG, "Mac went away: ${info.serviceName}")
                resolving.remove(info.serviceName)
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
     * Ask Android for the details of one found service, then add it to the list.
     *
     * Two guards, both learned the hard way with NsdManager:
     *  - Android throws if you resolve while another resolve is still running
     *    for that service, so we keep a set of in-flight names.
     *  - There's exactly one ResolveListener slot per NsdManager in practice,
     *    so we keep a single reusable listener rather than allocating per call.
     */
    private fun requestResolve(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        if (name in resolving) return
        resolving.add(name)

        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(result: NsdServiceInfo) {
                resolving.remove(result.serviceName)
                val resolvedName = result.serviceName ?: return
                Log.i(TAG, "Resolved Mac: $resolvedName")

                val device = MacDevice(id = resolvedName, name = resolvedName)
                val current = _macs.value
                // Replace if we already had it (a re-resolve), else append.
                val updated = current.filterNot { it.id == device.id } + device
                _macs.value = updated.sortedBy { it.name.lowercase() }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving.remove(info.serviceName)
                // Resolution failures are common and usually transient (the
                // Mac's record can vanish mid-resolve). Not fatal: the next
                // announcement will try again.
                Log.w(TAG, "Resolve failed for ${info.serviceName}: code $errorCode")
            }
        }

        resolveListener = listener
        try {
            nsdManager.resolveService(info, listener)
        } catch (e: Exception) {
            // The race we were guarding against still happened — forget it and
            // let a later announcement retry rather than crashing the app.
            resolving.remove(name)
            Log.w(TAG, "resolveService threw: ${e.message}")
        }
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
        resolveListener = null
        resolving.clear()
        _macs.value = emptyList()
    }
}
