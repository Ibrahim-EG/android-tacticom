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
    private val writeLock = Any()

    fun getLocalIPv4(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) return addr.hostAddress!!
                }
            }
        } catch (e: Exception) { Log.e(TAG, "IP error", e) }
        return "127.0.0.1"
    }

    fun start(ctx: Context) {
        if (running) return
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
                        sendHello(s)
                        thread(name = "tcp-reader") { readLoop(s) }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Server error", e) }
        }
    }

    fun connectToPeer(peer: Peer, onConnected: () -> Unit, onFailed: () -> Unit) {
        thread(name = "tcp-client") {
            try {
                disconnect()
                Log.d(TAG, "Connecting to ${peer.ip}:$TCP_PORT")
                val s = Socket()
                s.connect(InetSocketAddress(peer.ip, TCP_PORT), 3000) // 3s timeout
                s.tcpNoDelay = true
                activeSocket = s
                Bus.connectedPeer.value = peer
                sendHello(s)
                onConnected() // ONLY fire callback when socket is physically open
                readLoop(s)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                onFailed()
                disconnect()
            }
        }
    }

    private fun sendHello(s: Socket) {
        val json = JSONObject().put("type", "hello").put("id", Store.myId).put("name", Store.activeName())
        writeFrame(0, json.toString().toByteArray(), s)
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
            "chat" -> {
                val text = json.optString("text")
                val time = json.optLong("time", System.currentTimeMillis())
                val msg = ChatMessage(java.util.UUID.randomUUID().toString(), text, time, false)
                val peer = Bus.connectedPeer.value ?: return
                Store.saveChatMessage(peer.id, msg)
                if (Bus.currentChatPeer.value?.id == peer.id) Bus.chatMessages.value = Store.getChatHistory(peer.id)
                else Bus.toastMsg.value = "New message from ${peer.name}"
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
            "ring" -> com.tacticom.app.Controller.ringLocal(json.optString("from", "Someone"))
        }
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(buf, off, n - off); if (r < 0) return null; off += r }
        return buf
    }

    private fun writeFrame(type: Byte, data: ByteArray, s: Socket? = activeSocket) {
        if (s == null || s.isClosed) return
        synchronized(writeLock) {
            runCatching {
                val out = s.getOutputStream()
                val payload = ByteArray(data.size + 1); payload[0] = type
                System.arraycopy(data, 0, payload, 1, data.size)
                out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                out.write(payload); out.flush()
            }
        }
    }

    fun sendJson(bytes: ByteArray) = writeFrame(0, bytes)
    fun sendAudio(bytes: ByteArray) = writeFrame(1, bytes)
    
    fun sendChatMessage(text: String) {
        val json = JSONObject().put("type", "chat").put("text", text).put("time", System.currentTimeMillis())
        sendJson(json.toString().toByteArray())
    }

    fun disconnect() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        Bus.connectedPeer.value = null
        Bus.callState.value = CallState.IDLE
        Bus.activeCallPeer.value = null
    }

    fun ringPeer(peer: Peer) {
        sendJson(JSONObject().put("type", "ring").put("from", Store.activeName()).toString().toByteArray())
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
                        val json = JSONObject().put("id", Store.myId).put("name", Store.activeName())
                        s.send(DatagramPacket(json.toString().toByteArray(), json.toString().toByteArray().size, InetAddress.getByName(UDP_GROUP), UDP_PORT))
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
