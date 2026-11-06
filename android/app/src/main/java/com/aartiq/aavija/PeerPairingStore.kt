package com.aartiq.aavija

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * One phone we have paired with as a CLIENT.
 *
 * [tokenHex] is the session token the other phone minted for us. [fingerprint]
 * is the certificate fingerprint we saw during that first pairing; the other
 * phone regenerates its certificate on every launch, so the token — not the
 * fingerprint — is what authenticates us on later visits.
 */
data class PairedPeer(
    val id: String,
    val name: String,
    val tokenHex: String,
    val fingerprint: String?,
    val host: String?,
    val port: Int,
)

/**
 * Where outbound pairings live. Kept separate from [PrefsPairingStore], which
 * stores the tokens WE mint for Macs and other phones connecting to us. The two
 * directions are independent: being paired with a Mac must not make a phone
 * think it is paired with a different phone.
 */
interface PeerPairingStore {
    fun peers(): List<PairedPeer>
    fun find(id: String): PairedPeer?
    fun save(peer: PairedPeer)
    fun updateEndpoint(id: String, host: String, port: Int)
    fun forget(id: String)

    /** Which paired phone the UI is currently acting on. */
    var activePeerId: String?
}

/** SharedPreferences-backed store, so a relaunch keeps phone pairings. */
class PrefsPeerPairingStore(context: Context) : PeerPairingStore {

    private val prefs: SharedPreferences =
        context.applicationContext
            .getSharedPreferences("aavija_peer_pairing", Context.MODE_PRIVATE)

    override var activePeerId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE, value).apply()
        }

    @Synchronized
    override fun peers(): List<PairedPeer> {
        val root = readRoot() ?: return emptyList()
        return root.keys().asSequence().mapNotNull { id ->
            readPeer(root.optJSONObject(id), id)
        }.toList()
    }

    @Synchronized
    override fun find(id: String): PairedPeer? {
        val root = readRoot() ?: return null
        return readPeer(root.optJSONObject(id), id)
    }

    @Synchronized
    override fun save(peer: PairedPeer) {
        val root = readRoot() ?: JSONObject()
        root.put(
            peer.id,
            JSONObject()
                .put("name", peer.name)
                .put("token", peer.tokenHex)
                .put("fingerprint", peer.fingerprint)
                .put("host", peer.host)
                .put("port", peer.port),
        )
        prefs.edit().putString(KEY_PEERS, root.toString()).apply()
    }

    @Synchronized
    override fun updateEndpoint(id: String, host: String, port: Int) {
        val current = find(id) ?: return
        if (current.host == host && current.port == port) return
        save(current.copy(host = host, port = port))
    }

    @Synchronized
    override fun forget(id: String) {
        val root = readRoot() ?: return
        root.remove(id)
        prefs.edit().putString(KEY_PEERS, root.toString()).apply()
        if (activePeerId == id) activePeerId = null
    }

    private fun readRoot(): JSONObject? {
        val raw = prefs.getString(KEY_PEERS, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null // a corrupt blob loses pairings; it must never crash the app
        }
    }

    private fun readPeer(obj: JSONObject?, id: String): PairedPeer? {
        if (obj == null) return null
        val token = obj.optString("token")
        // Only a real 32-byte token is usable; skip a damaged row.
        val valid = token.length == 64 && runCatching {
            PairingCrypto.fromHex(token).size == 32
        }.getOrDefault(false)
        if (!valid) return null
        return PairedPeer(
            id = id,
            name = obj.optString("name").ifEmpty { id },
            tokenHex = token,
            fingerprint = obj.optString("fingerprint").ifEmpty { null },
            host = obj.optString("host").ifEmpty { null },
            port = obj.optInt("port", 0),
        )
    }

    companion object {
        private const val KEY_PEERS = "peers"
        private const val KEY_ACTIVE = "active_peer"
    }
}
