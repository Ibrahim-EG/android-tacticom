package com.tacticom.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tacticom.app.Bus
import com.tacticom.app.Peer
import com.tacticom.app.Session
import com.tacticom.app.Store
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
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
    val peers = ConcurrentHashMap<String, Peer>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var mcastSocket: MulticastSocket? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    @Volatile var running = false

    private val outQueue = LinkedBlockingQueue<Pair<Socket, Pair<Byte, ByteArray>>>(1000)
    
    // Session management
    private val hostedSessions = ConcurrentHashMap<String, SessionState>()
    private val clientConnections = ConcurrentHashMap<String, Socket>() // sessionId -> socket to host

    class SessionState(
        val id: String,
        val name: String,
        val password: String?,
        val members: ConcurrentHashMap<Socket, String> = ConcurrentHashMap() // socket -> name
    )

    fun start(ctx: Context) {
        if (running) return
        running = true
        startWriter()
        startServer()
        startDiscovery(ctx)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { mcastSocket?.close() }
        runCatching { mcastLock?.release() }
        hostedSessions.clear()
        clientConnections.clear()
    }

    private fun startWriter() {
        thread(name = "tcp-writer") {
            while (running) {
                val frame = try { outQueue.poll(300, TimeUnit.MILLISECONDS) } catch (_: Exception) { null } ?: continue
                val s = frame.first
                if (s.isClosed) continue
                try {
                    val out = s.getOutputStream()
                    val payload = ByteArray(frame.second.second.size + 1)
                    payload[0] = frame.second.first
                    System.arraycopy(frame.second.second, 0, payload, 1, frame.second.second.size)
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
                    thread(name = "tcp-reader") { readLoop(s) }
                }
            } catch (e: Exception) { Log.e(TAG, "Server error", e) }
        }
    }

    fun connectToHost(hostIp: String, hostPort: Int) {
        thread(name = "tcp-client") {
            try {
                Log.d(TAG, "Connecting to $hostIp:$hostPort")
                val s = Socket()
                s.connect(InetSocketAddress(hostIp, hostPort), 3000)
                s.tcpNoDelay = true
                thread(name = "tcp-reader") { readLoop(s) }
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                Bus.toastMsg.value = "Failed to connect"
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val ins = s.getInputStream()
            while (running && !s.isClosed) {
                val lenBytes = readExact(ins, 4) ?: break
                val len = ByteBuffer.wrap(lenBytes).int
                if (len <= 0 || len > 2_000_000) break
                val body = readExact(ins, len) ?: break
                if (body[0].toInt() == 0) handleJson(JSONObject(String(body, 1, len - 1)), s)
                else handleAudio(body.copyOfRange(1, len), s)
            }
        } catch (e: Exception) { Log.e(TAG, "Read error", e) }
        cleanupConnection(s)
    }

    private fun handleJson(json: JSONObject, s: Socket) {
        Log.d(TAG, "Received: ${json.toString()}")
        when (json.optString("type")) {
            "ring" -> {
                val from = json.optString("from", "Unknown")
                val sessionId = json.optString("session_id")
                val sessionName = json.optString("session_name", "Call")
                val hostIp = json.optString("host_ip")
                val hostPort = json.optInt("host_port")
                val locked = json.optBoolean("locked", false)
                
                com.tacticom.app.Controller.onIncomingRing(from, sessionId, sessionName, hostIp, hostPort, locked)
            }
            "join_session" -> {
                val sessionId = json.optString("session_id")
                val password = json.optString("password", "")
                val memberName = json.optString("name", "Unknown")
                
                val session = hostedSessions[sessionId]
                if (session != null) {
                    if (session.password != null && hashPassword(sessionId, password) != session.password) {
                        sendJson(s, JSONObject().put("type", "join_error").put("reason", "Incorrect password").toString().toByteArray())
                        return
                    }
                    session.members[s] = memberName
                    broadcastSessionUpdate(sessionId)
                    sendJson(s, JSONObject().put("type", "join_success").put("session_id", sessionId).toString().toByteArray())
                }
            }
            "leave_session" -> {
                cleanupConnection(s)
            }
            "tx_start", "tx_stop" -> {
                val sessionId = json.optString("session_id")
                val session = hostedSessions[sessionId]
                if (session != null) {
                    // Broadcast to all other members
                    session.members.keys.forEach { memberSocket ->
                        if (memberSocket != s && !memberSocket.isClosed) {
                            sendJson(memberSocket, json.toString().toByteArray())
                        }
                    }
                }
            }
            "join_success" -> {
                val sessionId = json.optString("session_id")
                clientConnections[sessionId] = s
                com.tacticom.app.Controller.onJoinSuccess(sessionId)
            }
            "join_error" -> {
                val reason = json.optString("reason", "Failed to join")
                Bus.toastMsg.value = reason
                com.tacticom.app.Controller.onJoinFailed()
            }
            "session_update" -> {
                val sessionId = json.optString("session_id")
                val members = json.optString("members", "").split("|").filter { it.isNotEmpty() }
                if (clientConnections.containsKey(sessionId)) {
                    Bus.sessionMembers.value = members
                }
            }
        }
    }

    private fun handleAudio(data: ByteArray, sender: Socket) {
        // Find which session this socket belongs to
        for ((sessionId, session) in hostedSessions) {
            if (session.members.containsKey(sender)) {
                // Broadcast to all other members
                session.members.keys.forEach { memberSocket ->
                    if (memberSocket != sender && !memberSocket.isClosed) {
                        sendAudio(memberSocket, data)
                    }
                }
                // Also play locally if we're in this session
                if (Bus.currentSession.value?.id == sessionId) {
                    com.tacticom.app.Controller.audio?.feed(data)
                }
                break
            }
        }
        
        // If this is from a host connection, play it
        for ((sessionId, socket) in clientConnections) {
            if (socket == sender && Bus.currentSession.value?.id == sessionId) {
                com.tacticom.app.Controller.audio?.feed(data)
                break
            }
        }
    }

    private fun cleanupConnection(s: Socket) {
        runCatching { s.close() }
        
        // Remove from all sessions
        for ((sessionId, session) in hostedSessions) {
            if (session.members.containsKey(s)) {
                session.members.remove(s)
                broadcastSessionUpdate(sessionId)
                if (session.members.isEmpty()) {
                    hostedSessions.remove(sessionId)
                    Bus.sessions.value = Bus.sessions.value.filter { it.id != sessionId }
                }
            }
        }
        
        // Remove from client connections
        clientConnections.entries.removeIf { it.value == s }
    }

    private fun broadcastSessionUpdate(sessionId: String) {
        val session = hostedSessions[sessionId] ?: return
        val members = session.members.values.joinToString("|")
        val update = JSONObject()
            .put("type", "session_update")
            .put("session_id", sessionId)
            .put("members", members)
            .toString()
            .toByteArray()
        
        session.members.keys.forEach { memberSocket ->
            if (!memberSocket.isClosed) {
                sendJson(memberSocket, update)
            }
        }
    }

    fun createSession(sessionName: String, password: String?, targetIp: String, targetPort: Int): String {
        val sessionId = java.util.UUID.randomUUID().toString().take(8)
        val passHash = if (password != null && password.isNotEmpty()) hashPassword(sessionId, password) else null
        hostedSessions[sessionId] = SessionState(sessionId, sessionName, passHash)
        
        val session = Session(
            id = sessionId,
            name = sessionName,
            hostId = Store.myId,
            hostName = Store.activeName(),
            hostIp = getLocalIPv4(),
            hostPort = TCP_PORT,
            locked = passHash != null,
            memberCount = 0
        )
        Bus.sessions.value = Bus.sessions.value + session
        
        return sessionId
    }

    fun sendRing(targetIp: String, targetPort: Int, sessionId: String, sessionName: String, locked: Boolean) {
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(targetIp, targetPort), 3000)
                s.tcpNoDelay = true
                val json = JSONObject()
                    .put("type", "ring")
                    .put("from", Store.activeName())
                    .put("session_id", sessionId)
                    .put("session_name", sessionName)
                    .put("host_ip", getLocalIPv4())
                    .put("host_port", TCP_PORT)
                    .put("locked", locked)
                sendJson(s, json.toString().toByteArray())
                s.close()
            } catch (e: Exception) {
                Log.e(TAG, "Ring failed", e)
            }
        }
    }

    fun joinSession(sessionId: String, hostIp: String, hostPort: Int, password: String?) {
        clientConnections[sessionId] = null // Will be set when connected
        connectToHost(hostIp, hostPort)
        
        // Wait a bit for connection, then send join
        thread {
            Thread.sleep(500)
            val s = clientConnections[sessionId]
            if (s != null) {
                val json = JSONObject()
                    .put("type", "join_session")
                    .put("session_id", sessionId)
                    .put("password", password ?: "")
                    .put("name", Store.activeName())
                sendJson(s, json.toString().toByteArray())
            }
        }
    }

    fun leaveSession(sessionId: String) {
        val s = clientConnections[sessionId]
        if (s != null) {
            sendJson(s, JSONObject().put("type", "leave_session").toString().toByteArray())
            runCatching { s.close() }
            clientConnections.remove(sessionId)
        }
        
        Bus.currentSession.value = null
        Bus.sessionMembers.value = emptyList()
        com.tacticom.app.Controller.stopAudio()
    }

    fun transmitStart(sessionId: String) {
        val s = clientConnections[sessionId] ?: return
        sendJson(s, JSONObject().put("type", "tx_start").put("session_id", sessionId).toString().toByteArray())
    }

    fun transmitStop(sessionId: String) {
        val s = clientConnections[sessionId] ?: return
        sendJson(s, JSONObject().put("type", "tx_stop").put("session_id", sessionId).toString().toByteArray())
    }

    private fun hashPassword(sessionId: String, password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$sessionId:$password".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sendJson(s: Socket, bytes: ByteArray) { outQueue.offer(s to (0.toByte() to bytes)) }
    private fun sendAudio(s: Socket, bytes: ByteArray) { outQueue.offer(s to (1.toByte() to bytes)) }

    fun getLocalIPv4(): String {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) return addr.hostAddress!!
                }
            }
        } catch (e: Exception) { Log.e(TAG, "IP error", e) }
        return "127.0.0.1"
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(buf, off, n - off); if (r < 0) return null; off += r }
        return buf
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
