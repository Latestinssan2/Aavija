package com.aartiq.aavija

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds OTHER PHONES on this Wi-Fi running Aavija.
 *
 * Both phones advertise the same `_gestureshare._tcp` service (the listener
 * half each one already runs). This class is the second half: it browses that
 * same service so a phone can act as the CLIENT for a phone-to-phone session.
 *
 * ### Why resolving is unavoidable here
 * [MacDiscovery] deliberately never resolves, because the Mac always dials the
 * phone and the phone only needs a name to display. A phone client is the one
 * dialling this time, so it MUST learn the peer's address + port. Android's
 * resolve call is the flakiest part of NsdManager (one at a time, and it can
 * fail on a freshly announced record), so we:
 *
 *  - queue resolves and run exactly one at a time,
 *  - retry by letting the periodic service announcement enqueue the name again,
 *  - skip our own service by its TXT `id` (two identical phone models advertise
 *    the same name, so a name check alone is not enough).
 *
 * The list is kept in stable name order and updated in place. A row must never
 * jump while the user is reaching for it.
 */
class PeerDiscovery(
    context: Context,
    private val selfDeviceId: String,
    private val selfPortProvider: () -> Int?,
) {

    companion object {
        private const val TAG = "AavijaPeerDiscovery"

        /** Same service type the phone server advertises. */
        const val PHONE_SERVICE_TYPE = "_gestureshare._tcp"

        /** Is a reported service type the phone service? Lenient normalising. */
        fun isPhoneType(reported: String?): Boolean {
            val normalised = (reported ?: "")
                .trim()
                .trimEnd('.')
                .removeSuffix(".local")
                .trimEnd('.')
                .lowercase()
            return normalised == PHONE_SERVICE_TYPE
        }
    }

    /**
     * One reachable phone. [name] is the Bonjour instance name (what the user
     * sees); [id] is the per-install random id from the TXT record. Pairing is
     * stored against [id] because a Bonjour name can change between runs when
     * two identical phone models race for the same name.
     */
    data class PhonePeer(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    private val _peers = MutableStateFlow<List<PhonePeer>>(emptyList())
    val peers: StateFlow<List<PhonePeer>> = _peers.asStateFlow()

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Service names already queued or resolved, so announcements are cheap. */
    private val requested = mutableSetOf<String>()

    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for phones: $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!isPhoneType(info.serviceType)) return
                val name = info.serviceName ?: return
                // Already listed, or already waiting in the resolve queue.
                if (_peers.value.any { it.name == name }) return
                if (!requested.add(name)) return
                pending.addLast(info)
                pumpResolve()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                requested.remove(name)
                pending.removeAll { it.serviceName == name }
                _peers.value = _peers.value.filterNot { it.name == name }
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
        nsdManager.discoverServices(
            PHONE_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    fun stop() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.d(TAG, "stopServiceDiscovery: ${e.message}")
            }
        }
        discoveryListener = null
        requested.clear()
        pending.clear()
        resolving = false
        _peers.value = emptyList()
    }

    /** Run the next queued resolve. NsdManager allows only one at a time. */
    private fun pumpResolve() {
        if (resolving || pending.isEmpty() || discoveryListener == null) return
        val info = pending.removeFirst()
        val name = info.serviceName ?: run {
            pumpResolve()
            return
        }
        resolving = true

        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Could not resolve '$name': code $errorCode")
                    requested.remove(name)
                    resolving = false
                    pumpResolve()
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolving = false
                    val resolvedName = resolved.serviceName ?: name
                    requested.remove(resolvedName)

                    if (discoveryListener != null && !isSelf(resolved)) {
                        val deviceId = stableId(resolved) ?: resolvedName
                        toPeer(resolved, deviceId, resolvedName)?.let(::upsert)
                    }
                    pumpResolve()
                }
            })
        } catch (e: Exception) {
            // resolveService throws if another resolve is somehow in flight.
            // Drop this one and let the next announcement try again.
            Log.d(TAG, "resolveService threw for '$name': ${e.message}")
            requested.remove(name)
            resolving = false
            pumpResolve()
        }
    }

    /** Our own advertisement also shows up in the browse results. Skip it. */
    private fun isSelf(info: NsdServiceInfo): Boolean {
        if (selfDeviceId.isNotEmpty()) {
            val id = stableId(info)
            if (id == selfDeviceId) return true
        }
        // Fallback for older records without the TXT attribute.
        val ownPort = selfPortProvider()
        return ownPort != null && info.port == ownPort
    }

    /** The per-install id from the TXT record, or null for a legacy record. */
    private fun stableId(info: NsdServiceInfo): String? {
        val raw = info.attributes?.get("id") ?: return null
        val id = raw.toString(Charsets.UTF_8).trim()
        return id.ifEmpty { null }
    }

    private fun toPeer(
        info: NsdServiceInfo,
        deviceId: String,
        name: String,
    ): PhonePeer? {
        val address: InetAddress? = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }
                ?: info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.host
        }
        val host = address?.hostAddress ?: return null
        if (address?.isLoopbackAddress == true) return null
        if (info.port <= 0) return null
        return PhonePeer(id = deviceId, name = name, host = host, port = info.port)
    }

    private fun upsert(peer: PhonePeer) {
        val current = _peers.value
        val index = current.indexOfFirst { it.id == peer.id }
        if (index >= 0) {
            if (current[index] == peer) return
            _peers.value = current.toMutableList().also { it[index] = peer }
        } else {
            Log.i(TAG, "Found phone: ${peer.name} at ${peer.host}:${peer.port}")
            _peers.value = (current + peer)
                .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        }
    }
}
