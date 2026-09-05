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
        audio?.startCapture()
        audio?.startPlayback()
        enableProximity()
    }

    fun onIncomingConnection(fromName: String) {
        // Ring when someone connects to you
        TacticomService.instance?.startRing(fromName, 15000)
        
        // Auto-start audio for the connection
        initAudio()
        audio?.startCapture()
        audio?.startPlayback()
        enableProximity()
    }

    fun disconnect() {
        stopAudio()
        disableProximity()
        NetworkManager.disconnect()
    }

    fun startTransmit() {
        Bus.isTransmitting.value = true
        audio?.transmitting = true
    }

    fun stopTransmit() {
        Bus.isTransmitting.value = false
        audio?.transmitting = false
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
