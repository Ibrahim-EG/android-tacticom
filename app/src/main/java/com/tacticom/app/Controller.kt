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
        if (audio == null) audio = AudioEngine(ctx).apply { onChunk = { NetworkManager.sendAudio(it) } }
    }

    fun callPeer(peer: Peer) {
        Bus.activeCallPeer.value = peer
        Bus.callState.value = CallState.CALLING
        NetworkManager.connectToPeer(peer, 
            onConnected = { 
                Log.d(TAG, "Socket open, sending call request")
                NetworkManager.sendCallRequest() 
            }, 
            onFailed = { 
                Bus.toastMsg.value = "Failed to call"
                Bus.callState.value = CallState.IDLE
            }
        )
    }

    fun acceptCall() {
        Bus.callState.value = CallState.CONNECTED
        NetworkManager.sendCallAccept()
        startAudio()
        stopRinging()
    }

    fun declineCall() {
        NetworkManager.sendCallDecline()
        handleDisconnect()
    }

    fun hangUp() { 
        NetworkManager.sendCallDecline()
        handleDisconnect() 
    }

    fun handleDisconnect() {
        stopRinging()
        stopAudio()
        NetworkManager.disconnect()
    }

    fun startAudio() { initAudio(); audio?.startCapture(); audio?.startPlayback() }
    fun stopAudio() { audio?.stopCapture(); audio?.stopPlayback() }

    fun startRinging(name: String) { TacticomService.instance?.startRing(name, 45000) }
    fun stopRinging() { TacticomService.instance?.stopRing() }

    fun setEarpiece(on: Boolean) {
        audio?.earpiece = on; audio?.applyRouting()
        if (Bus.callState.value == CallState.CONNECTED) { audio?.stopPlayback(); audio?.startPlayback() }
    }
}
