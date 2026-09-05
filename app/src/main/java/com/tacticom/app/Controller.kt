package com.tacticom.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager

object Controller {
    @Volatile var audio: AudioEngine? = null
    @Volatile var appContext: Context? = null
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastClear: Runnable? = null
    @Volatile private var ringResultReceived = false
    @Volatile var incomingRingSessionId: String? = null

    fun startService(c: Context) {
        appContext = c.applicationContext
        val i = Intent(c, TacticomService::class.java)
        try { Context::class.java.getMethod("startForegroundService", Intent::class.java).invoke(c, i) }
        catch (e: Exception) { c.startService(i) }
    }

    fun showToast(msg: String, durationMs: Long) {
        toastHandler.post {
            Bus.toastMsg.value = msg
            toastClear?.let { toastHandler.removeCallbacks(it) }
            val r = Runnable { Bus.toastMsg.value = "" }
            toastClear = r
            toastHandler.postDelayed(r, durationMs)
        }
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
        showToast("Session \"$finalName\" created", 4000)
    }

    fun callPeer(peer: Peer) {
        val sessionId = NetworkManager.createSession("Call with ${peer.name}", null)
        NetworkManager.sendRing(peer.ip, peer.port, sessionId, "Call with ${peer.name}", false)
        ringResultReceived = false
        showToast("Ringing ${peer.name}...", 46000)
        toastHandler.postDelayed({
            if (!ringResultReceived) showToast("${peer.name} did not answer", 7000)
        }, 46000)
    }

    fun onRingResult(msg: String) {
        ringResultReceived = true
        showToast(msg, 7000)
    }

    fun onIncomingRing(from: String, sessionId: String, sessionName: String, hostIp: String, hostPort: Int, locked: Boolean) {
        incomingRingSessionId = sessionId
        TacticomService.instance?.startRing(from, 45000, hostIp, hostPort)
        if (Bus.sessions.value.none { it.id == sessionId }) {
            Bus.sessions.value = Bus.sessions.value + Session(sessionId, sessionName, from, from, hostIp, hostPort, locked, 0)
        }
    }

    fun enterSession(session: Session, password: String?) {
        if (session.hostId != Store.myId && session.id == incomingRingSessionId) {
            stopRinging("answered")
            incomingRingSessionId = null
        }
        if (session.hostId == Store.myId) {
            NetworkManager.enterHostedSession(session.id)
            Bus.currentSession.value = session
            Bus.sessionMembers.value = listOf(Store.activeName())
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
        showToast("Joined session", 4000)
    }

    fun onJoinFailed() {
        Bus.currentSession.value = null
        Bus.sessionMembers.value = emptyList()
        stopAudio()
    }

    fun onDisconnected() {
        Bus.currentSession.value = null
        Bus.sessionMembers.value = emptyList()
        stopAudio()
        showToast("Disconnected from session", 4000)
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

    fun stopRinging(reason: String = "stopped") {
        TacticomService.instance?.stopRing(reason)
    }

    fun stopAudio() {
        audio?.stopCapture()
        audio?.stopPlayback()
    }
}
