package com.tacticom.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tacticom.app.Bus
import com.tacticom.app.Peer
import com.tacticom.app.Session
import com.tacticom.app.Store
import org.json.JSONArray
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
    const val TCP_PORT = 54321
    private const val UDP_PORT = 54322
    private const val UDP_GROUP = "239.255.255.250"

    @Volatile var serverSocket: ServerSocket? = null
    val peers = ConcurrentHashMap<String, Peer>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var mcastSocket: MulticastSocket? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    @Volatile var running = false

    private val outQueue = LinkedBlockingQueue<Pair<Socket, Pair<Byte, ByteArray>>>(1000)
    private val hostedSessions = ConcurrentHashMap<String, SessionState>()
    private val clientConnections = ConcurrentHashMap<String, Socket>()
    private val remoteSessions = ConcurrentHashMap<String, Session>() // Tracks sessions hosted by OTHER devices

    class SessionState(val id: String, val name: String, val password: String?) {
        val createdAt = System.currentTimeMillis()
        @Volatile var hostIn = false
        val members = ConcurrentHashMap<Socket, String>()
    }

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
        remoteSessions.clear()
    }

    private fun startWriter() {
        thread(name = "tcp-writer") {
            while (running) {
                val frame = try { outQueue.poll(300, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
                if (frame == null) continue
                val s = frame.first
                if (s.isClosed) continue
                try {
                    val out = s.getOutputStream()
                    val data = frame.second.second
                    val payload = ByteArray(data.size + 1)
                    payload[0] = frame.second.first
                    System.arraycopy(data, 0, payload, 1, data.size)
                    out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                    out.write(payload)
                    out.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Write error", e)
                }
            }
        }
    }

    private fun writeDirect(s: Socket, type: Byte, bytes: ByteArray) {
        try {
            val out = s.getOutputStream()
            val payload = ByteArray(bytes.size + 1)
            payload[0] = type
            System.arraycopy(bytes, 0, payload, 1, bytes.size)
            out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
            out.write(payload)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Direct write error", e)
        }
    }

    private fun startServer() {
        thread(name = "tcp-server") {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                while (running) {
                    val s = serverSocket?.accept() ?: break
                    s.tcpNoDelay = true
                    thread(name = "tcp-reader") { readLoop(s) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    fun connectToHost(hostIp: String, hostPort: Int, onConnected: (Socket) -> Unit) {
        thread(name = "tcp-client") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(hostIp, hostPort), 3000)
                s.tcpNoDelay = true
                onConnected(s)
                readLoop(s)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                Bus.toastMsg.value = "Failed to connect to host"
                com.tacticom.app.Controller.onJoinFailed()
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val ins = s.getInputStream()
            while (running && !s.isClosed) {
                val lenBytes = readExact(ins, 4) ?: break
                val len = ByteBuffer.wrap(lenBytes).int
                if (len <= 0 || len > 2000000) break
                val body = readExact(ins, len) ?: break
                if (body[0].toInt() == 0) {
                    handleJson(JSONObject(String(body, 1, len - 1)), s)
                } else {
                    handleAudio(body.copyOfRange(1, len), s)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read error", e)
        }
        cleanupConnection(s)
    }

    private fun handleJson(json: JSONObject, s: Socket) {
        val type = json.optString("type")
        if (type == "ring") {
            com.tacticom.app.Controller.onIncomingRing(
                json.optString("from", "Unknown"),
                json.optString("session_id"),
                json.optString("session_name", "Call"),
                json.optString("host_ip"),
                json.optInt("host_port"),
                json.optBoolean("locked", false)
            )
        } else if (type == "join_session") {
            val sessionId = json.optString("session_id")
            val password = json.optString("password", "")
            val memberName = json.optString("name", "Unknown")
            val session = hostedSessions[sessionId]
            if (session != null) {
                if (session.password != null && hashPassword(sessionId, password) != session.password) {
                    writeDirect(s, 0.toByte(), JSONObject().put("type", "join_error").put("reason", "Incorrect password").toString().toByteArray())
                    return
                }
                session.members[s] = memberName
                writeDirect(s, 0.toByte(), JSONObject().put("type", "join_success").put("session_id", sessionId).toString().toByteArray())
                broadcastSessionUpdate(sessionId)
            }
        } else if (type == "leave_session") {
            cleanupConnection(s)
        } else if (type == "tx_start" || type == "tx_stop") {
            val session = hostedSessions[json.optString("session_id")]
            if (session != null) {
                for (m in session.members.keys) {
                    if (m != s && !m.isClosed) writeDirect(m, 0.toByte(), json.toString().toByteArray())
                }
            }
        } else if (type == "join_success") {
            clientConnections[json.optString("session_id")] = s
            com.tacticom.app.Controller.onJoinSuccess(json.optString("session_id"))
        } else if (type == "join_error") {
            Bus.toastMsg.value = json.optString("reason", "Failed to join")
            com.tacticom.app.Controller.onJoinFailed()
            runCatching { s.close() }
        } else if (type == "session_update") {
            val sessionId = json.optString("session_id")
            val members = json.optString("members", "").split("|").filter { it.isNotEmpty() }
            if (clientConnections.containsKey(sessionId) || hostedSessions.containsKey(sessionId)) {
                Bus.sessionMembers.value = members
            }
        }
    }

    private fun handleAudio(data: ByteArray, sender: Socket) {
        val currentSessionId = Bus.currentSession.value?.id ?: return
        com.tacticom.app.Controller.audio?.feed(data)
        if (hostedSessions.containsKey(currentSessionId)) {
            broadcastAudio(currentSessionId, sender, data)
        }
    }

    private fun cleanupConnection(s: Socket) {
        runCatching { s.close() }
        for ((sessionId, session) in hostedSessions) {
            if (session.members.containsKey(s)) {
                session.members.remove(s)
                broadcastSessionUpdate(sessionId)
                if (!session.hostIn && session.members.isEmpty()) {
                    hostedSessions.remove(sessionId)
                }
            }
        }
        clientConnections.entries.removeIf { it.value == s }
        updateBusSessions()
    }

    private fun memberNames(session: SessionState): List<String> {
        val list = mutableListOf<String>()
        if (session.hostIn) list.add(Store.activeName())
        list.addAll(session.members.values)
        return list
    }

    private fun broadcastSessionUpdate(sessionId: String) {
        val session = hostedSessions[sessionId] ?: return
        val names = memberNames(session)
        val update = JSONObject().put("type", "session_update").put("session_id", sessionId).put("members", names.joinToString("|")).toString().toByteArray()
        session.members.keys.forEach { if (!it.isClosed) writeDirect(it, 0.toByte(), update) }
        updateBusSessions()
        if (Bus.currentSession.value?.id == sessionId) Bus.sessionMembers.value = names
    }

    // Recalculates the global session list from local hosted sessions + remote discovered sessions
    private fun updateBusSessions() {
        val localSessions = hostedSessions.values.map { state ->
            val names = memberNames(state)
            Session(state.id, state.name, Store.myId, Store.activeName(), getLocalIPv4(), TCP_PORT, state.password != null, names.size)
        }
        val allSessions = localSessions + remoteSessions.values
        Bus.sessions.value = allSessions.sortedBy { it.name }
    }

    fun createSession(sessionName: String, password: String?): String {
        val sessionId = java.util.UUID.randomUUID().toString().take(8)
        val passHash = if (!password.isNullOrEmpty()) hashPassword(sessionId, password) else null
        hostedSessions[sessionId] = SessionState(sessionId, sessionName, passHash)
        updateBusSessions()
        return sessionId
    }

    fun sendRing(targetIp: String, targetPort: Int, sessionId: String, sessionName: String, locked: Boolean) {
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(targetIp, targetPort), 3000)
                s.tcpNoDelay = true
                val json = JSONObject().put("type", "ring").put("from", Store.activeName()).put("session_id", sessionId)
                    .put("session_name", sessionName).put("host_ip", getLocalIPv4()).put("host_port", TCP_PORT).put("locked", locked)
                writeDirect(s, 0.toByte(), json.toString().toByteArray())
                s.close()
            } catch (e: Exception) {
                Log.e(TAG, "Ring failed", e)
                Bus.toastMsg.value = "Could not reach device"
            }
        }
    }

    fun enterHostedSession(sessionId: String) {
        val session = hostedSessions[sessionId] ?: return
        session.hostIn = true
        broadcastSessionUpdate(sessionId)
    }

    fun leaveHostedSession(sessionId: String) {
        val session = hostedSessions[sessionId] ?: return
        session.hostIn = false
        if (session.members.isEmpty()) {
            hostedSessions.remove(sessionId)
        } else {
            broadcastSessionUpdate(sessionId)
        }
        updateBusSessions()
    }

    fun joinSession(sessionId: String, hostIp: String, hostPort: Int, password: String?) {
        connectToHost(hostIp, hostPort) { socket ->
            val json = JSONObject().put("type", "join_session").put("session_id", sessionId)
                .put("password", password ?: "").put("name", Store.activeName())
            writeDirect(socket, 0.toByte(), json.toString().toByteArray())
        }
    }

    fun leaveSession(sessionId: String) {
        val s = clientConnections.remove(sessionId)
        if (s != null) {
            writeDirect(s, 0.toByte(), JSONObject().put("type", "leave_session").toString().toByteArray())
            runCatching { s.close() }
        }
    }

    fun broadcastAudio(sessionId: String, sender: Socket?, data: ByteArray) {
        val session = hostedSessions[sessionId]
        if (session != null) {
            session.members.keys.forEach { m -> if (m != sender && !m.isClosed) outQueue.offer(m to (1.toByte() to data)) }
        } else {
            val hostSocket = clientConnections[sessionId]
            if (hostSocket != null && !hostSocket.isClosed) outQueue.offer(hostSocket to (1.toByte() to data))
        }
    }

    private fun hashPassword(sessionId: String, password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$sessionId:$password".toByteArray()).joinToString("") { "%02x".format(it) }
    }

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
                val buf = ByteArray(1024)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size); s.receive(pkt)
                    runCatching {
                        val json = JSONObject(String(pkt.data, 0, pkt.length)); val id = json.optString("id")
                        if (id.isNotEmpty() && id != Store.myId) {
                            peers[id] = Peer(id, json.optString("name", "?"), pkt.address.hostAddress ?: "", TCP_PORT, false)
                            lastSeen[id] = System.currentTimeMillis(); Bus.peers.value = peers.values.sortedBy { it.name }

                            // Parse remote sessions broadcasted by this peer
                            val remoteSessionsJson = json.optJSONArray("sessions")
                            if (remoteSessionsJson != null) {
                                val remotePeerIp = pkt.address.hostAddress ?: ""
                                val hostName = json.optString("name", "Unknown")
                                // Clear old sessions from this host to handle deletions
                                remoteSessions.entries.removeIf { it.value.hostId == id }
                                for (i in 0 until remoteSessionsJson.length()) {
                                    val sJson = remoteSessionsJson.getJSONObject(i)
                                    val remoteSession = Session(
                                        id = sJson.getString("id"),
                                        name = sJson.getString("name"),
                                        hostId = id,
                                        hostName = hostName,
                                        hostIp = remotePeerIp,
                                        hostPort = TCP_PORT,
                                        locked = sJson.optBoolean("locked", false),
                                        memberCount = sJson.optInt("memberCount", 0)
                                    )
                                    remoteSessions[remoteSession.id] = remoteSession
                                }
                                updateBusSessions()
                            }
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
                        // Include hosted sessions in the broadcast payload
                        val localSessionsJson = JSONArray()
                        for (state in hostedSessions.values) {
                            val names = memberNames(state)
                            localSessionsJson.put(
                                JSONObject()
                                    .put("id", state.id)
                                    .put("name", state.name)
                                    .put("locked", state.password != null)
                                    .put("memberCount", names.size)
                            )
                        }
                        val payload = JSONObject()
                            .put("id", Store.myId)
                            .put("name", Store.activeName())
                            .put("sessions", localSessionsJson)
                            .toString().toByteArray()
                        s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(UDP_GROUP), UDP_PORT))
                    }
                }; Thread.sleep(2000)
            }
        }
        
        thread(name = "prune") {
            while (running) {
                Thread.sleep(5000)
                val now = System.currentTimeMillis()
                val stale = lastSeen.filter { now - it.value > 10000 }.keys
                if (stale.isNotEmpty()) {
                    stale.forEach { 
                        peers.remove(it)
                        lastSeen.remove(it)
                    }
                    Bus.peers.value = peers.values.sortedBy { it.name }
                    
                    // Remove remote sessions hosted by peers that went offline
                    remoteSessions.entries.removeIf { entry -> !peers.containsKey(entry.value.hostId) }
                    updateBusSessions()
                }

                // Host: delete empty local sessions older than 60s that nobody entered
                var localChanged = false
                hostedSessions.values.toList().forEach { ses ->
                    if (!ses.hostIn && ses.members.isEmpty() && now - ses.createdAt > 60000) {
                        hostedSessions.remove(ses.id)
                        localChanged = true
                    }
                }
                if (localChanged) updateBusSessions()
            }
        }
    }
}
