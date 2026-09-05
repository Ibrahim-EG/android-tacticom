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

    fun callPeer(peer: Peer) {
        val sessionId = NetworkManager.createSession("Call with ${peer.name}", null)
        val session = Session(sessionId, "Call with ${peer.name}", Store.myId, Store.activeName(), NetworkManager.getLocalIPv4(), NetworkManager.TCP_PORT, false, 1)
        Bus.currentSession.value = session
        Bus.sessions.value = Bus.sessions.value + session
        Bus.sessionMembers.value = listOf(Store.activeName())
        
        NetworkManager.sendRing(peer.ip, peer.port, sessionId, session.name, false)
        initAudio()
        audio?.startCapture()
        audio?.startPlayback()
    }

    fun onIncomingRing(from: String, sessionId: String, sessionName: String, hostIp: String, hostPort: Int, locked: Boolean) {
        TacticomService.instance?.startRing(from, 45000)
        val session = Session(sessionId, sessionName, from, from, hostIp, hostPort, locked, 1)
        if (Bus.sessions.value.none { it.id == sessionId }) {
            Bus.sessions.value = Bus.sessions.value + session
        }
    }

    fun joinSession(session: Session, password: String?) {
        Bus.currentSession.value = session
        NetworkManager.joinSession(session.id, session.hostIp, session.hostPort, password)
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
        Bus.currentSession.value?.let { session ->
            NetworkManager.leaveSession(session.id)
        }
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
