package com.tacticom.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tacticom.app.Bus
import com.tacticom.app.CallState
import com.tacticom.app.ChatMessage
import com.tacticom.app.Peer
import com.tacticom.app.Store
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object NetworkManager {
    private const val TAG = "P2P"
    private const val TCP_PORT = 54321
    private const val UDP_PORT = 54322
    private const val UDP_GROUP = "239.255.255.250"

    @Volatile var serverSocket: ServerSocket? = null
    @Volatile var activeSocket: Socket? = null
    val peers = ConcurrentHashMap<String, Peer>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var mcastSocket: MulticastSocket? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    @Volatile var running = false

    // THE FIX: every outgoing frame is queued and written by ONE background
    // thread. Android blocks socket writes on the UI thread, which is why
    // "Accept" / chat / bell never reached the other phone before.
    private val outQueue = LinkedBlockingQueue<Pair<Byte, ByteArray>>(1000)

    fun start(ctx: Context) {
        if (running) return
        running = true
        startWriter()
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

    private fun startWriter() {
        thread(name = "tcp-writer") {
            while (running) {
                val frame = try { outQueue.poll(300, TimeUnit.MILLISECONDS) } catch (_: Exception) { null } ?: continue
                val s = activeSocket
                if (s == null || s.isClosed) continue
                try {
                    val out = s.getOutputStream()
                    val payload = ByteArray(frame.second.size + 1)
                    payload[0] = frame.first
                    System.arraycopy(frame.second, 0, payload, 1, frame.second.size)
                    out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                    out.write(payload)
                    out.flush()
                } catch (e: Exception) { Log.e(TAG, "Write error", e) }
            }
        }
    }

    private fun startServer() {
        thread(name = "tcp-server") {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                Log.d(TAG, "Server listening on $TCP_PORT")
                while (running) {
                    val s = serverSocket?.accept() ?: break
                    s.tcpNoDelay = true
                    Log.d(TAG, "Incoming connection from ${s.inetAddress.hostAddress}")
                    if (activeSocket != null && !activeSocket!!.isClosed) s.close()
                    else {
                        activeSocket = s
                        sendHello()
                        thread(name = "tcp-reader") { readLoop(s) }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Server error", e) }
        }
    }

    fun connectToPeer(peer: Peer, onConnected: () -> Unit, onFailed: () -> Unit) {
        thread(name = "tcp-client") {
            try {
                runCatching { activeSocket?.close() }
                activeSocket = null
                Log.d(TAG, "Connecting to ${peer.ip}:$TCP_PORT")
                val s = Socket()
                s.connect(InetSocketAddress(peer.ip, TCP_PORT), 3000)
                s.tcpNoDelay = true
                activeSocket = s
                Bus.connectedPeer.value = peer
                sendHello()
                onConnected()
                readLoop(s)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                onFailed()
                disconnect()
            }
        }
    }

    private fun sendHello() {
        val json = JSONObject().put("type", "hello").put("id", Store.myId).put("name", Store.activeName())
        sendJson(json.toString().toByteArray())
    }

    private fun readLoop(s: Socket) {
        try {
            val ins = s.getInputStream()
            while (running && !s.isClosed) {
                val lenBytes = readExact(ins, 4) ?: break
                val len = ByteBuffer.wrap(lenBytes).int
                if (len <= 0 || len > 2_000_000) break
                val body = readExact(ins, len) ?: break
                if (body[0].toInt() == 0) handleJson(JSONObject(String(body, 1, len - 1)))
                else if (Bus.callState.value == CallState.CONNECTED) com.tacticom.app.Controller.audio?.feed(body.copyOfRange(1, len))
            }
        } catch (e: Exception) { Log.e(TAG, "Read error", e) }
        com.tacticom.app.Controller.handleDisconnect()
    }

    private fun handleJson(json: JSONObject) {
        Log.d(TAG, "Received: ${json.toString()}")
        when (json.optString("type")) {
            "hello" -> {
                val id = json.optString("id")
                val name = json.optString("name")
                val peer = peers.values.firstOrNull { it.id == id } ?: Peer(id, name, activeSocket?.inetAddress?.hostAddress ?: "", TCP_PORT, false)
                Bus.connectedPeer.value = peer
            }
            "call_req" -> {
                val peer = Bus.connectedPeer.value ?: return
                Bus.activeCallPeer.value = peer
                Bus.callState.value = CallState.RINGING
                com.tacticom.app.Controller.startRinging(json.optString("from", "Unknown"))
            }
            "call_acc" -> {
                Bus.callState.value = CallState.CONNECTED
                com.tacticom.app.Controller.stopRinging()
                com.tacticom.app.Controller.startAudio()
            }
            "call_dec" -> com.tacticom.app.Controller.handleDisconnect()
        }
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(buf, off, n - off); if (r < 0) return null; off += r }
        return buf
    }

    fun sendJson(bytes: ByteArray) { outQueue.offer(0 to bytes) }

    fun sendAudio(bytes: ByteArray) {
        if (outQueue.remainingCapacity() == 0) outQueue.poll()
        outQueue.offer(1 to bytes)
    }

    fun sendCallRequest() {
        sendJson(JSONObject().put("type", "call_req").put("from", Store.activeName()).toString().toByteArray())
    }

    fun sendCallAccept() {
        sendJson(JSONObject().put("type", "call_acc").toString().toByteArray())
    }

    fun sendCallDecline() {
        sendJson(JSONObject().put("type", "call_dec").toString().toByteArray())
    }

    fun disconnect() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        Bus.connectedPeer.value = null
        Bus.callState.value = CallState.IDLE
        Bus.activeCallPeer.value = null
    }

    private fun startDiscovery(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mcastLock = wm.createMulticastLock("tacticom_mcast").apply { setReferenceCounted(false); acquire() }
        thread(name = "udp-rx") {
            try {
                val s = MulticastSocket(UDP_PORT); s.reuseAddress = true; mcastSocket = s
                s.joinGroup(InetAddress.getByName(UDP_GROUP))
                val buf = ByteArray(512)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size); s.receive(pkt)
                    runCatching {
                        val json = JSONObject(String(pkt.data, 0, pkt.length)); val id = json.optString("id")
                        if (id.isNotEmpty() && id != Store.myId) {
                            peers[id] = Peer(id, json.optString("name", "?"), pkt.address.hostAddress ?: "", TCP_PORT, false)
                            lastSeen[id] = System.currentTimeMillis(); Bus.peers.value = peers.values.sortedBy { it.name }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "UDP RX error", e) }
        }
        thread(name = "udp-tx") {
            while (running) {
                runCatching {
                    val s = mcastSocket
                    if (s != null) {
                        val payload = JSONObject().put("id", Store.myId).put("name", Store.activeName()).toString().toByteArray()
                        s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(UDP_GROUP), UDP_PORT))
                    }
                }; Thread.sleep(2000)
            }
        }
        thread(name = "prune") {
            while (running) {
                Thread.sleep(5000); val now = System.currentTimeMillis()
                val stale = lastSeen.filter { now - it.value > 10000 }.keys
                if (stale.isNotEmpty()) { stale.forEach { peers.remove(it); lastSeen.remove(it) }; Bus.peers.value = peers.values.sortedBy { it.name } }
            }
        }
    }
}
