package com.tacticom.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.tacticom.app.audio.AudioEngine
import com.tacticom.app.net.NetworkManager

object Controller {
    private const val TAG = "Controller"
    @Volatile var audio: AudioEngine? = null
    @Volatile var appContext: Context? = null
    private var proxLock: PowerManager.WakeLock? = null

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

    fun connectToPeer(peer: Peer) {
        initAudio()
        NetworkManager.connectToPeer(peer)
        // Only start PLAYBACK on connection, not capture
        // Capture only starts when PTT is pressed
        audio?.startPlayback()
        enableProximity()
    }

    fun disconnect() {
        stopAudio()
        disableProximity()
        NetworkManager.disconnect()
    }

    // Start capturing only when PTT is pressed
    fun startTransmit() {
        Bus.isTransmitting.value = true
        audio?.transmitting = true
        audio?.startCapture() // Start mic now
    }

    // Stop capturing when PTT is released
    fun stopTransmit() {
        Bus.isTransmitting.value = false
        audio?.transmitting = false
        audio?.stopCapture() // Stop mic now
    }

    fun startAudio() { 
        initAudio()
        audio?.startPlayback()
    }
    
    fun stopAudio() { 
        audio?.stopCapture()
        audio?.stopPlayback() 
    }

    fun setEarpiece(on: Boolean) {
        audio?.earpiece = on
        audio?.applyRouting()
        if (Bus.connectedPeer.value != null) {
            audio?.stopPlayback()
            audio?.startPlayback()
        }
    }

    private fun enableProximity() {
        val ctx = appContext ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (proxLock == null) {
            proxLock = pm.newWakeLock(32, "tacticom:prox")
        }
        if (proxLock?.isHeld == false) proxLock?.acquire()
    }

    private fun disableProximity() {
        runCatching { proxLock?.let { if (it.isHeld) it.release() } }
    }
}
