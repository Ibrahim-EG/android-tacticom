package com.tacticom.app.net

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Every device hosts its own mini-server (the old Python server, reborn in Kotlin).
 * Clients — including this device's own UI via 127.0.0.1 — connect over TCP.
 */
class SessionServer(private val onRing: (String) -> Unit) {

    class Conn(val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val ins: InputStream = socket.getInputStream()
        @Volatile var session: Session? = null
        @Volatile var name: String = "OP-" + (1000..9999).random()
        fun sendJson(bytes: ByteArray) = synchronized(this) { Proto.writeFrame(out, Proto.TYPE_JSON, bytes) }
        fun sendPcm(bytes: ByteArray) = synchronized(this) { Proto.writeFrame(out, Proto.TYPE_PCM, bytes) }
    }

    class Session(val id: String, val name: String, val passHash: String?) {
        val clients = ConcurrentHashMap<Conn, String>()
    }

    @Volatile var running = false
    private var server: ServerSocket? = null
    val sessions = ConcurrentHashMap<String, Session>()
    private val allConns = CopyOnWriteArrayList<Conn>()

    fun port(): Int = server?.localPort ?: 0

    fun start() {
        val ss = ServerSocket(0)
        server = ss
        running = true
        thread(name = "server-accept") {
            while (running) {
                try {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    thread(name = "server-conn") { handle(Conn(s)) }
                } catch (_: Exception) { break }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        sessions.clear()
        allConns.clear()
    }

    private fun hash(sid: String, pass: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$sid:$pass".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun handle(conn: Conn) {
        allConns.add(conn)
        try {
            sendLobby(conn)
            while (running) {
                val frame = Proto.readFrame(conn.ins) ?: break
                if (frame.first == Proto.TYPE_PCM) {
                    conn.session?.let { broadcastPcm(it, conn, frame.second) }
                } else {
                    handleJson(conn, Proto.parse(frame.second))
                }
            }
        } catch (_: Exception) {
        } finally {
            allConns.remove(conn)
            leave(conn)
            runCatching { conn.socket.close() }
        }
    }

    private fun handleJson(conn: Conn, j: JSONObject) {
        j.optString("profile", "").trim().take(24).let { if (it.isNotEmpty()) conn.name = it }
        when (j.optString("type")) {
            "hello" -> sendLobby(conn)
            "create_session" -> {
                val id = j.optString("name", "").trim().uppercase().take(32)
                if (id.isEmpty()) { conn.sendJson(Proto.json("type" to "error", "reason" to "Give the session a name.")); return }
                if (sessions.containsKey(id)) { conn.sendJson(Proto.json("type" to "error", "reason" to "Session already exists — join it instead.")); return }
                val pass = j.optString("password", "")
                sessions[id] = Session(id, id, if (pass.isNotEmpty()) hash(id, pass) else null)
                join(conn, sessions[id]!!)
            }
            "join_session" -> {
                val id = j.optString("id", "").uppercase()
                val sess = sessions[id]
                if (sess == null) {
                    conn.sendJson(Proto.json("type" to "error", "reason" to "That session just ended."))
                    sendLobby(conn)
                    return
                }
                if (sess.passHash != null) {
                    val pass = j.optString("password", "").trim()
                    if (hash(id, pass) != sess.passHash) {
                        conn.sendJson(Proto.json("type" to "error", "reason" to "Incorrect access code."))
                        return
                    }
                }
                join(conn, sess)
            }
            "leave_session" -> leave(conn)
            "tx_start", "tx_stop" -> {
                val sess = conn.session ?: return
                val bytes = Proto.json("type" to j.optString("type"))
                for ((c, _) in sess.clients) if (c !== conn) runCatching { c.sendJson(bytes) }
            }
            "ring" -> onRing(j.optString("from", "Someone"))
        }
    }

    private fun join(conn: Conn, sess: Session) {
        leave(conn)
        val taken = sess.clients.values.toSet()
        var name = conn.name
        var i = 2
        while (name in taken) { name = "${conn.name}-$i"; i++ }
        sess.clients[conn] = name
        conn.session = sess
        conn.sendJson(Proto.json(
            "type" to "session_joined",
            "session_id" to sess.id,
            "session_name" to sess.name,
            "locked" to (sess.passHash != null),
            "name" to name,
            "count" to sess.clients.size
        ))
        broadcastPresence(sess)
        pushLobbyAll()
    }

    private fun leave(conn: Conn) {
        val sess = conn.session ?: return
        sess.clients.remove(conn)
        conn.session = null
        if (sess.clients.isEmpty()) sessions.remove(sess.id)
        broadcastPresence(sess)
        pushLobbyAll()
    }

    private fun broadcastPresence(sess: Session) {
        val bytes = Proto.json(
            "type" to "presence",
            "count" to sess.clients.size,
            "names" to sess.clients.values.joinToString("|")
        )
        for ((c, _) in sess.clients) runCatching { c.sendJson(bytes) }
    }

    private fun broadcastPcm(sess: Session, sender: Conn, data: ByteArray) {
        for ((c, _) in sess.clients) if (c !== sender) runCatching { c.sendPcm(data) }
    }

    private fun lobbyJson(): ByteArray {
        val list = sessions.values.map {
            "${it.id}~${it.name}~${if (it.passHash != null) 1 else 0}~${it.clients.size}"
        }
        return Proto.json("type" to "lobby", "sessions" to list.joinToString("|"))
    }

    private fun sendLobby(conn: Conn) = runCatching { conn.sendJson(lobbyJson()) }

    private fun pushLobbyAll() {
        for (c in allConns) runCatching { c.sendJson(lobbyJson()) }
    }
}
