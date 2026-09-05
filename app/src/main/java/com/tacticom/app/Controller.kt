package com.tacticom.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager

object Controller {
    private const val TAG = "Controller"
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
        if (audio == null) audio = AudioEngine(ctx).apply { onChunk = { chunk ->
            Bus.currentSession.value?.let { session ->
                NetworkManager.sendAudio(session.id, chunk)
            }
        } }
    }

    fun callPeer(peer: Peer) {
        // Create a session and ring the peer
        val sessionId = NetworkManager.createSession("Call", null, peer.ip, peer.port)
        NetworkManager.sendRing(peer.ip, peer.port, sessionId, "Call", false)
        
        Bus.toastMsg.value = "Ringing ${peer.name}..."
    }

    fun onIncomingRing(from: String, sessionId: String, sessionName: String, hostIp: String, hostPort: Int, locked: Boolean) {
        // Show notification and ring for 45 seconds
        TacticomService.instance?.startRing(from, 45000)
        
        // Add to sessions list
        val session = Session(sessionId, sessionName, from, from, hostIp, hostPort, locked, 1)
        Bus.sessions.value = Bus.sessions.value + session
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
        Bus.currentSession.value?.let { session ->
            Bus.isTransmitting.value = true
            audio?.transmitting = true
            NetworkManager.transmitStart(session.id)
        }
    }

    fun stopTransmit() {
        Bus.currentSession.value?.let { session ->
            Bus.isTransmitting.value = false
            audio?.transmitting = false
            NetworkManager.transmitStop(session.id)
        }
    }

    fun startAudio() { 
        initAudio()
        audio?.startCapture()
        audio?.startPlayback()
    }
    
    fun stopAudio() { 
        audio?.stopCapture()
        audio?.stopPlayback() 
    }
}
