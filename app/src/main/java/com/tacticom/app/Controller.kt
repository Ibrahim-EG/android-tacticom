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
        c.startForegroundService(Intent(c, TacticomService::class.java))
    }

    fun initAudio() {
        val ctx = appContext ?: return
        if (audio == null) {
            audio = AudioEngine(ctx).apply {
                onChunk = { NetworkManager.sendAudio(it) }
            }
        }
    }

    fun callPeer(peer: Peer) {
        initAudio()
        NetworkManager.connectToPeer(peer)
        audio?.startCapture()
        audio?.startPlayback()
    }

    fun hangUp() { NetworkManager.disconnect() }
    fun ringPeer(peer: Peer) { NetworkManager.ringPeer(peer) }
    fun ringLocal(from: String) { TacticomService.instance?.startRing(from) }

    fun setTransmit(on: Boolean) {
        Bus.transmitting.value = on
        audio?.transmitting = on
    }

    fun setLive(on: Boolean) {
        Bus.liveMode.value = on
        setTransmit(on)
    }

    fun setEarpiece(on: Boolean) {
        Bus.earpiece.value = on
        audio?.earpiece = on
        audio?.applyRouting()
        if (Bus.isInCall.value) {
            audio?.stopPlayback()
            audio?.startPlayback()
        }
    }
}
