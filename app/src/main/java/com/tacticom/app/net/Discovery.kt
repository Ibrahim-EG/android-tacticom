package com.tacticom.app.net

import android.content.Context
import android.net.wifi.WifiManager
import com.tacticom.app.Bus
import com.tacticom.app.Peer
import com.tacticom.app.Store
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * UDP multicast beacons on 239.255.255.250:50505.
 * Multicast is bridged at link-layer on most home routers, which is the best
 * chance of seeing peers across mixed 192.168.1.x / 192.168.0.x subnets.
 * Manual IPs (set in Profile screen) also get unicast beacons as a fallback.
 */
class Discovery(private val ctx: Context) {
    companion object {
        const val GROUP = "239.255.255.250"
        const val PORT = 50505
    }

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var mcastLock: WifiManager.MulticastLock? = null
    private var myPort = 0

    fun start(serverPort: Int) {
        myPort = serverPort
        running = true
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mcastLock = wm.createMulticastLock("tacticom_mcast").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "discovery-rx") { rxLoop() }
        thread(name = "discovery-tx") { txLoop() }
        thread(name = "discovery-prune") { pruneLoop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { mcastLock?.release() }
        peers.clear()
        publish()
    }

    private fun rxLoop() {
        try {
            val s = MulticastSocket(PORT)
            s.reuseAddress = true
            socket = s
            s.joinGroup(InetAddress.getByName(GROUP))
            val buf = ByteArray(512)
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                runCatching {
                    val json = JSONObject(String(pkt.data, 0, pkt.length))
                    val id = json.optString("id")
                    if (id.isNotEmpty() && id != Store.myId) {
                        peers[id] = Peer(
                            id,
                            json.optString("name", "?"),
                            pkt.address.hostAddress ?: "",
                            json.optInt("port", 0),
                            false
                        )
                        lastSeen[id] = System.currentTimeMillis()
                        publish()
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun txLoop() {
        while (running) {
            try {
                val s = socket
                if (s != null) {
                    val payload = Proto.json(
                        "id" to Store.myId,
                        "name" to Store.activeName(),
                        "port" to myPort
                    )
                    s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(GROUP), PORT))
                    for (ip in Store.manualIps) {
                        runCatching {
                            s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), PORT))
                        }
                    }
                }
            } catch (_: Exception) { }
            Thread.sleep(2000)
        }
    }

    private fun pruneLoop() {
        while (running) {
            Thread.sleep(3000)
            val now = System.currentTimeMillis()
            val stale = lastSeen.filter { now - it.value > 7000 }.keys
            if (stale.isNotEmpty()) {
                stale.forEach {
                    peers.remove(it)
                    lastSeen.remove(it)
                }
                publish()
            }
        }
    }

    private fun publish() {
        Bus.peers.value = peers.values.sortedBy { it.name }
    }
}
