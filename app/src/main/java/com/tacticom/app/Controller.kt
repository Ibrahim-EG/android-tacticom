package com.tacticom.app

import android.content.Context
import android.content.Intent
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager
import org.json.JSONObject

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
        if (audio == null) audio = AudioEngine(ctx).apply { onChunk = { NetworkManager.sendAudio(it) } }
    }

    fun callPeer(peer: Peer) {
        Bus.activePeer.value = peer
        Bus.callState.value = CallState.CALLING
        NetworkManager.connectToPeer(peer)
        NetworkManager.sendJson(JSONObject().put("type", "incoming_call").put("from", Store.activeName()).toString().toByteArray())
    }

    fun acceptCall() {
        Bus.callState.value = CallState.CONNECTED
        NetworkManager.sendJson(JSONObject().put("type", "call_accepted").toString().toByteArray())
        startAudio()
        stopRinging()
    }

    fun declineCall() {
        NetworkManager.sendJson(JSONObject().put("type", "call_declined").toString().toByteArray())
        handleDisconnect()
    }

    fun hangUp() { handleDisconnect() }

    fun handleDisconnect() {
        stopRinging()
        stopAudio()
        NetworkManager.disconnect()
    }

    fun startAudio() { initAudio(); audio?.startCapture(); audio?.startPlayback() }
    fun stopAudio() { audio?.stopCapture(); audio?.stopPlayback() }

    fun startRinging(name: String) { TacticomService.instance?.startRing(name, 45000) }
    fun stopRinging() { TacticomService.instance?.stopRing() }

    fun ringPeer(peer: Peer) { NetworkManager.ringPeer(peer) }
    fun ringLocal(from: String) { TacticomService.instance?.startRing(from, 15000) } // Manual bell is 15s

    fun setEarpiece(on: Boolean) {
        Bus.earpiece.value = on // Note: earpiece flow removed from Bus to simplify, but kept here for future
        audio?.earpiece = on; audio?.applyRouting()
        if (Bus.callState.value == CallState.CONNECTED) { audio?.stopPlayback(); audio?.startPlayback() }
    }
}
