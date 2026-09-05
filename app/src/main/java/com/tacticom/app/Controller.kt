package com.tacticom.app

import android.content.Context
import android.content.Intent
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager
import org.json.JSONObject
import kotlin.concurrent.thread

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

    fun openChat(peer: Peer) {
        Bus.currentChatPeer.value = peer
        Bus.chatMessages.value = Store.getChatHistory(peer.id)
        thread { NetworkManager.ensureConnected(peer) }
    }

    fun sendChat(text: String) {
        val peer = Bus.currentChatPeer.value ?: return
        if (text.isBlank()) return
        val msg = ChatMessage(java.util.UUID.randomUUID().toString(), text, System.currentTimeMillis(), true)
        Store.saveChatMessage(peer.id, msg)
        Bus.chatMessages.value = Store.getChatHistory(peer.id)
        NetworkManager.sendChatMessage(text)
    }

    fun callPeer(peer: Peer) {
        Bus.activeCallPeer.value = peer
        Bus.callState.value = CallState.CALLING
        thread {
            val s = NetworkManager.ensureConnected(peer)
            if (s != null) {
                NetworkManager.sendJson(JSONObject().put("type", "incoming_call").put("from", Store.activeName()).toString().toByteArray())
            } else {
                Bus.callState.value = CallState.IDLE
            }
        }
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

    fun hangUp() { 
        NetworkManager.sendJson(JSONObject().put("type", "call_declined").toString().toByteArray())
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

    fun ringPeer(peer: Peer) { NetworkManager.ringPeer(peer) }
    fun ringLocal(from: String) { TacticomService.instance?.startRing(from, 15000) }

    fun setEarpiece(on: Boolean) {
        audio?.earpiece = on; audio?.applyRouting()
        if (Bus.callState.value == CallState.CONNECTED) { audio?.stopPlayback(); audio?.startPlayback() }
    }
}
