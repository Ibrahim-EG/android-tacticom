package com.tacticom.app.net

import com.tacticom.app.Bus
import com.tacticom.app.SessionInfo
import org.json.JSONObject
import java.net.Socket
import kotlin.concurrent.thread

/** This device's client connection to a host (remote device or 127.0.0.1 = self). */
class HostConnection(
    private val ip: String,
    private val port: Int,
    private val onRingLocal: (String) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile var connected = false
    private val writeLock = Any()

    var onPcm: ((ByteArray) -> Unit)? = null
    var onTx: ((Boolean) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    fun connect(profileName: String) {
        thread(name = "client-connect") {
            try {
                val s = Socket(ip, port)
                s.tcpNoDelay = true
                socket = s
                connected = true
                sendJson(Proto.json("type" to "hello", "profile" to profileName))
                readerLoop(s)
            } catch (_: Exception) {
            } finally {
                connected = false
                onDisconnect?.invoke()
            }
        }
    }

    private fun readerLoop(s: Socket) {
        while (connected) {
            val frame = Proto.readFrame(s.getInputStream()) ?: break
            if (frame.first == Proto.TYPE_PCM) {
                onPcm?.invoke(frame.second)
            } else {
                dispatch(Proto.parse(frame.second))
            }
        }
    }

    private fun dispatch(j: JSONObject) {
        when (j.optString("type")) {
            "lobby" -> {
                Bus.lobby.value = j.optString("sessions", "").split("|")
                    .filter { it.isNotEmpty() }
                    .map {
                        val p = it.split("~")
                        SessionInfo(p[0], p.getOrNull(1) ?: p[0], p.getOrNull(2) == "1", p.getOrNull(3)?.toIntOrNull() ?: 0)
                    }
            }
            "session_joined" -> {
                Bus.inSession.value = SessionInfo(
                    j.optString("session_id"), j.optString("session_name"),
                    j.optBoolean("locked"), j.optInt("count")
                )
                Bus.mySessionName.value = j.optString("name")
            }
            "presence" -> {
                Bus.presence.value = j.optString("names", "").split("|").filter { it.isNotEmpty() }
                Bus.inSession.value?.let { Bus.inSession.value = it.copy(count = j.optInt("count")) }
            }
            "tx_start" -> onTx?.invoke(true)
            "tx_stop" -> onTx?.invoke(false)
            "error" -> Bus.toastMsg.value = j.optString("reason")
            "ring" -> onRingLocal(j.optString("from", "Someone"))
        }
    }

    fun sendJson(bytes: ByteArray) {
        val s = socket ?: return
        synchronized(writeLock) { runCatching { Proto.writeFrame(s.getOutputStream(), Proto.TYPE_JSON, bytes) } }
    }

    fun sendPcm(bytes: ByteArray) {
        val s = socket ?: return
        synchronized(writeLock) { runCatching { Proto.writeFrame(s.getOutputStream(), Proto.TYPE_PCM, bytes) } }
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
    }
}
