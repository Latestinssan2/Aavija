package com.aartiq.aavija

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds macOS computers and Windows PCs on this local Wi-Fi running Aavija.
 *
 * Browses for:
 * - macOS: `_aavija-mac._tcp`
 * - Windows: `_aavija-win._tcp`
 */
class MacDiscovery(context: Context) {

    companion object {
        private const val TAG = "AavijaDesktopDiscovery"
        /** Must match `MacAdvertiser.serviceType` on the Mac exactly. */
        const val MAC_SERVICE_TYPE = "_aavija-mac._tcp"
        /** Must match `DesktopAdvertiser.serviceType` on Windows exactly. */
        const val WIN_SERVICE_TYPE = "_aavija-win._tcp"

        fun normaliseType(reported: String?): String {
            return (reported ?: "")
                .trim()
                .trimEnd('.')
                .removeSuffix(".local")
                .trimEnd('.')
                .lowercase()
        }

        fun isMacType(reported: String?): Boolean = normaliseType(reported) == MAC_SERVICE_TYPE
        fun isWinType(reported: String?): Boolean = normaliseType(reported) == WIN_SERVICE_TYPE
        fun isOurType(reported: String?): Boolean = isMacType(reported) || isWinType(reported)
    }

    /** One desktop computer (Mac or Windows PC) the phone can see on this network. */
    data class MacDevice(
        /** Unique per service instance — the announced name, used as our id. */
        val id: String,
        /** Human-readable name, e.g. "Sandipkumar's MacBook Pro" or "Preet-PC". */
        val name: String,
        /** Whether this desktop target is a Windows PC. */
        val isWindows: Boolean = false,
    )

    private val _macs = MutableStateFlow<List<MacDevice>>(emptyList())
    /** Current device list. Empty = nothing found (yet). */
    val macs: StateFlow<List<MacDevice>> = _macs.asStateFlow()

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var macListener: NsdManager.DiscoveryListener? = null
    private var winListener: NsdManager.DiscoveryListener? = null

    /** Start watching for both Mac and Windows desktops. */
    fun start() {
        startMacDiscovery()
        startWinDiscovery()
    }

    private fun startMacDiscovery() {
        if (macListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!isMacType(info.serviceType)) return
                addDevice(info.serviceName, isWindows = false)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (!isMacType(info.serviceType)) return
                Log.i(TAG, "Mac went away: ${info.serviceName}")
                _macs.value = _macs.value.filterNot { it.id == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Stopped browsing $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "startDiscovery Mac failed: code $errorCode")
                macListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stopDiscovery Mac failed: code $errorCode")
            }
        }

        macListener = listener
        try {
            nsdManager.discoverServices(MAC_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Mac discovery: ${e.message}")
            macListener = null
        }
    }

    private fun startWinDiscovery() {
        if (winListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Browsing for Windows $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!isWinType(info.serviceType)) return
                addDevice(info.serviceName, isWindows = true)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (!isWinType(info.serviceType)) return
                Log.i(TAG, "Windows PC went away: ${info.serviceName}")
                _macs.value = _macs.value.filterNot { it.id == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Stopped browsing Windows $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "startDiscovery Windows failed: code $errorCode")
                winListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stopDiscovery Windows failed: code $errorCode")
            }
        }

        winListener = listener
        try {
            nsdManager.discoverServices(WIN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Windows discovery: ${e.message}")
            winListener = null
        }
    }

    /** Add (or re-announce) one desktop machine (Mac or Windows). */
    private fun addDevice(name: String?, isWindows: Boolean) {
        val id = name ?: return
        val current = _macs.value
        if (current.any { it.id == id }) return
        Log.i(TAG, "Found ${if (isWindows) "Windows PC" else "Mac"}: $id")
        _macs.value = (current + MacDevice(id = id, name = id, isWindows = isWindows))
            .sortedBy { it.name.lowercase() }
    }

    /** Stop browsing and forget everything found (called on teardown). */
    fun stop() {
        macListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (ignored: Exception) {}
        }
        winListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (ignored: Exception) {}
        }
        macListener = null
        winListener = null
        _macs.value = emptyList()
    }
}
