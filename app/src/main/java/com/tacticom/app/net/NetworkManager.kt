package com.tacticom.app.net

import android.content.Context
import android.net.wifi.WifiManager
import com.tacticom.app.Bus
import com.tacticom.app.Peer
import com.tacticom.app.Store
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object NetworkManager {
    @Volatile var serverSocket: ServerSocket? = null
    @Volatile var activeSocket: Socket? = null
    val peers = ConcurrentHashMap<String, Peer>()
    private var mcastSocket: MulticastSocket? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    @Volatile var running = false
    private var writeLock = Any()

    fun start(ctx: Context) {
        running = true
        startServer()
        startDiscovery(ctx)
    }

    fun stop() {
        running = false
        disconnect()
        runCatching { serverSocket?.close() }
        runCatching { mcastSocket?.close() }
        runCatching { mcastLock?.release() }
    }

    private fun startServer() {
        serverSocket = ServerSocket(0)
        thread(name = "server-accept") {
            while (running) {
                try {
                    val s = serverSocket?.accept() ?: break
                    s.tcpNoDelay = true
                    activeSocket = s
                    Bus.isInCall.value = true
                    thread(name = "server-reader") { readLoop(s) }
                } catch (_: Exception) {}
            }
        }
    }

    fun connectToPeer(peer: Peer) {
        if (activeSocket != null) disconnect()
        Bus.activeCall.value = peer
        thread(name = "client-connect") {
            try {
                val s = Socket(peer.ip, peer.port)
                s.tcpNoDelay = true
                activeSocket = s
                Bus.isInCall.value = true
                sendJson(JSONObject().put("type", "hello").put("name", Store.activeName()).toString().toByteArray())
                readLoop(s)
            } catch (e: Exception) {
                Bus.toastMsg.value = "Failed to connect to ${peer.name}"
                disconnect()
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val ins = s.getInputStream()
            while (running && Bus.isInCall.value) {
                val lenBytes = readExact(ins, 4) ?: break
                val len = ByteBuffer.wrap(lenBytes).int
                if (len <= 0 || len > 2_000_000) break
                val body = readExact(ins, len) ?: break
                
                if (body[0].toInt() == 0) { // JSON
                    val json = JSONObject(String(body, 1, len - 1))
                    handleJson(json)
                } else { // Audio (1)
                    Bus.receivingAudio.value = true
                    com.tacticom.app.Controller.audio?.feed(body.copyOfRange(1, len))
                }
            }
        } catch (_: Exception) {}
        disconnect()
    }

    private fun handleJson(json: JSONObject) {
        when (json.optString("type")) {
            "hello" -> {
                val name = json.optString("name", "Unknown")
                if (Bus.activeCall.value == null) {
                    val ip = activeSocket?.inetAddress?.hostAddress ?: ""
                    val peer = peers.values.firstOrNull { it.ip == ip } ?: Peer(ip, name, ip, 0, false)
                    Bus.activeCall.value = peer
                }
            }
            "ring" -> com.tacticom.app.Controller.ringLocal(json.optString("from", "Someone"))
        }
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    fun sendJson(bytes: ByteArray) {
        val s = activeSocket ?: return
        synchronized(writeLock) {
            runCatching {
                val out = s.getOutputStream()
                val payload = ByteArray(bytes.size + 1)
                payload[0] = 0
                System.arraycopy(bytes, 0, payload, 1, bytes.size)
                out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                out.write(payload)
                out.flush()
            }
        }
    }

    fun sendAudio(bytes: ByteArray) {
        val s = activeSocket ?: return
        synchronized(writeLock) {
            runCatching {
                val out = s.getOutputStream()
                val payload = ByteArray(bytes.size + 1)
                payload[0] = 1
                System.arraycopy(bytes, 0, payload, 1, bytes.size)
                out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                out.write(payload)
                out.flush()
            }
        }
    }

    fun disconnect() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        Bus.isInCall.value = false
        Bus.activeCall.value = null
        Bus.transmitting.value = false
        Bus.receivingAudio.value = false
        com.tacticom.app.Controller.audio?.stopCapture()
        com.tacticom.app.Controller.audio?.stopPlayback()
    }

    fun ringPeer(peer: Peer) {
        thread {
            runCatching {
                val s = Socket(peer.ip, peer.port)
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                val json = JSONObject().put("type", "ring").put("from", Store.activeName()).toString().toByteArray()
                val payload = ByteArray(json.size + 1)
                payload[0] = 0
                System.arraycopy(json, 0, payload, 1, json.size)
                out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                out.write(payload)
                out.flush()
                s.close()
            }
        }
    }

    private fun startDiscovery(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mcastLock = wm.createMulticastLock("tacticom_mcast").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "discovery-rx") {
            try {
                val s = MulticastSocket(50505)
                s.reuseAddress = true
                mcastSocket = s
                s.joinGroup(InetAddress.getByName("239.255.255.250"))
                val buf = ByteArray(512)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    runCatching {
                        val json = JSONObject(String(pkt.data, 0, pkt.length))
                        val id = json.optString("id")
                        if (id.isNotEmpty() && id != Store.myId) {
                            val p = Peer(id, json.optString("name", "?"), pkt.address.hostAddress ?: "", json.optInt("port", 0), false)
                            peers[id] = p
                            Bus.peers.value = peers.values.sortedBy { it.name }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        thread(name = "discovery-tx") {
            while (running) {
                runCatching {
                    val s = mcastSocket
                    if (s != null) {
                        val json = JSONObject().put("id", Store.myId).put("name", Store.activeName()).put("port", serverSocket?.localPort ?: 0)
                        val payload = json.toString().toByteArray()
                        s.send(DatagramPacket(payload, payload.size, InetAddress.getByName("239.255.255.250"), 50505))
                    }
                }
                Thread.sleep(2000)
            }
        }
    }
}
