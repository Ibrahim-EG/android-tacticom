package com.tacticom.app

import android.content.Context
import android.content.Intent
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.Discovery
import com.tacticom.app.net.HostConnection
import com.tacticom.app.net.Proto
import com.tacticom.app.net.SessionServer
import java.net.Socket
import kotlin.concurrent.thread

/** Bridge between the UI and the background service components. */
object Controller {
    @Volatile var server: SessionServer? = null
    @Volatile var discovery: Discovery? = null
    @Volatile var audio: AudioEngine? = null
    @Volatile var conn: HostConnection? = null

    fun startService(c: Context) {
        c.startForegroundService(Intent(c, TacticomService::class.java))
    }

    fun connect(peer: Peer) {
        conn?.close()
        Bus.connectedTo.value = peer
        val c = HostConnection(peer.ip, peer.port) { from -> TacticomService.ring(from) }
        c.onPcm = { audio?.feed(it) }
        c.onTx = { start -> if (start) audio?.startPlayback() }
        c.onDisconnect = {
            Bus.connectedTo.value = null
            Bus.lobby.value = emptyList()
            Bus.inSession.value = null
            Bus.presence.value = emptyList()
        }
        conn = c
        c.connect(Store.activeName())
    }

    fun connectSelf() {
        server?.let { connect(Peer(Store.myId, Store.activeName(), "127.0.0.1", it.port(), true)) }
    }

    fun createSession(name: String, pass: String) {
        conn?.sendJson(Proto.json(
            "type" to "create_session", "name" to name,
            "password" to pass, "profile" to Store.activeName()
        ))
    }

    fun joinSession(s: SessionInfo, pass: String) {
        conn?.sendJson(Proto.json(
            "type" to "join_session", "id" to s.id,
            "password" to pass, "profile" to Store.activeName()
        ))
        audio?.startCapture()
        audio?.startPlayback()
    }

    fun leave() {
        conn?.sendJson(Proto.json("type" to "leave_session"))
        Bus.inSession.value = null
        Bus.transmitting.value = false
        Bus.liveMode.value = false
        audio?.transmitting = false
        audio?.stopCapture()
        audio?.stopPlayback()
    }

    fun setTransmit(on: Boolean) {
        Bus.transmitting.value = on
        audio?.transmitting = on
        conn?.sendJson(Proto.json("type" to if (on) "tx_start" else "tx_stop"))
    }

    fun setLive(on: Boolean) {
        Bus.liveMode.value = on
        setTransmit(on)
    }

    fun setEarpiece(on: Boolean) {
        Bus.earpiece.value = on
        audio?.earpiece = on
        audio?.applyRouting()
        if (Bus.inSession.value != null) {
            audio?.stopPlayback()
            audio?.startPlayback()
        }
    }

    fun ring(peer: Peer) {
        thread {
            runCatching {
                val s = Socket(peer.ip, peer.port)
                s.tcpNoDelay = true
                Proto.writeFrame(
                    s.getOutputStream(), Proto.TYPE_JSON,
                    Proto.json("type" to "ring", "from" to Store.activeName())
                )
                s.close()
            }
        }
    }
}
