package com.tacticom.app

import android.content.Context
import android.content.Intent
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager

object Controller {
    @Volatile var audio: AudioEngine? = null
    @Volatile var appContext: Context? = null

    fun startService(c: Context) {
        appContext = c.applicationContext
        val i = Intent(c, TacticomService::class.java)
        try { Context::class.java.getMethod("startForegroundService", Intent::class.java).invoke(c, i) }
        catch (e: Exception) { c.startService(i) }
    }

    fun initAudio() {
        val ctx = appContext ?: return
        if (audio == null) audio = AudioEngine(ctx).apply {
            onChunk = { chunk ->
                Bus.currentSession.value?.let { session ->
                    NetworkManager.broadcastAudio(session.id, null, chunk)
                }
            }
        }
    }

    fun createSession(name: String, password: String?) {
        val finalName = if (name.isBlank()) "Session" else name
        NetworkManager.createSession(finalName, password)
        Bus.toastMsg.value = "Session \"$finalName\" created"
    }

    fun callPeer(peer: Peer) {
        val sessionId = NetworkManager.createSession("Call with ${peer.name}", null)
        NetworkManager.sendRing(peer.ip, peer.port, sessionId, "Call with ${peer.name}", false)
        Bus.toastMsg.value = "Ringing ${peer.name}..."
    }

    fun onIncomingRing(from: String, sessionId: String, sessionName: String, hostIp: String, hostPort: Int, locked: Boolean) {
        TacticomService.instance?.startRing(from, 45000)
        if (Bus.sessions.value.none { it.id == sessionId }) {
            Bus.sessions.value = Bus.sessions.value + Session(sessionId, sessionName, from, from, hostIp, hostPort, locked, 0)
        }
    }

    fun enterSession(session: Session, password: String?) {
        if (session.hostId == Store.myId) {
            NetworkManager.enterHostedSession(session.id)
            Bus.currentSession.value = session
            Bus.sessionMembers.value = listOf(Store.activeName())
            // Replaced startAudio() with direct calls
            initAudio()
            audio?.startCapture()
            audio?.startPlayback()
        } else {
            Bus.currentSession.value = session
            NetworkManager.joinSession(session.id, session.hostIp, session.hostPort, password)
        }
    }

    fun onJoinSuccess(sessionId: String) {
        initAudio()
        audio?.startCapture()
        audio?.startPlayback()
        Bus.toastMsg.value = "Joined session"
    }

    fun onJoinFailed() {
        Bus.currentSession.value = null
    }

    fun leaveSession() {
        val session = Bus.currentSession.value ?: return
        if (session.hostId == Store.myId) {
            NetworkManager.leaveHostedSession(session.id)
        } else {
            NetworkManager.leaveSession(session.id)
        }
        Bus.currentSession.value = null
        Bus.sessionMembers.value = emptyList()
        stopAudio()
    }

    fun startTransmit() {
        Bus.isTransmitting.value = true
        audio?.transmitting = true
    }

    fun stopTransmit() {
        Bus.isTransmitting.value = false
        audio?.transmitting = false
    }

    fun stopAudio() {
        audio?.stopCapture()
        audio?.stopPlayback()
    }
}
