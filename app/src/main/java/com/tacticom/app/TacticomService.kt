package com.tacticom.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tacticom.app.net.NetworkManager
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class TacticomService : Service() {
    companion object { @Volatile var instance: TacticomService? = null }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringWakeLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastRestart = System.currentTimeMillis()

    @Volatile private var ringCallbackIp: String? = null
    @Volatile private var ringCallbackPort = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        Controller.appContext = applicationContext
        Controller.initAudio()
        Thread { NetworkManager.start(this) }.start()
        acquireLocks()
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { restartNetwork() }
                override fun onLost(n: Network) { restartNetwork() }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { restartNetwork() }
            })
    }

    // Keeps CPU + Wi-Fi alive when the screen is locked so devices stay online
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tacticom:cpu").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = if (Build.VERSION.SDK_INT >= 29) {
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tacticom:wifi")
        } else {
            @Suppress("DEPRECATION") wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tacticom:wifi")
        }
        wifiLock?.setReferenceCounted(false)
        runCatching { wifiLock?.acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "exit") { stopRing("stopped"); stopForeground(true); stopSelf(); return START_NOT_STICKY }
        val notif = buildServiceNotification()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(1, notif, types)
        } else startForeground(1, notif)
        return START_STICKY
    }

    private fun restartNetwork() {
        val now = System.currentTimeMillis(); if (now - lastRestart < 3000) return; lastRestart = now
        NetworkManager.stop(); Thread { NetworkManager.start(this) }.start()
    }

    fun startRing(from: String, durationMs: Long = 45000, callbackIp: String? = null, callbackPort: Int = 0) {
        if (player != null) return
        ringCallbackIp = callbackIp
        ringCallbackPort = callbackPort
        handler.post {
            ringWakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tacticom:ring").apply { acquire(durationMs + 5000) }
            val uri = Store.activeProfile().ringtone?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = uri?.let { MediaPlayer.create(this@TacticomService, it) }?.apply { isLooping = true; start() }
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val effectClass = Class.forName("android.os.VibrationEffect")
                    val effect = effectClass.getMethod("createWaveform", LongArray::class.java, Int::class.javaPrimitiveType).invoke(null, longArrayOf(0, 800, 400), 0)
                    Vibrator::class.java.getMethod("vibrate", effectClass).invoke(vibrator, effect)
                } catch (e: Exception) { @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 800, 400), 0) }
            } else { @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 800, 400), 0) }

            val stopIntent = PendingIntent.getBroadcast(this, 2, Intent(this, RingStopReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, App.CH_RING).setContentTitle("TACTICOM — Incoming Call").setContentText("$from is calling...")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call).addAction(android.R.drawable.ic_delete, "IGNORE", stopIntent).setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, notif)
            handler.postDelayed({ stopRing("no_answer") }, durationMs)
        }
    }

    fun stopRing(reason: String = "stopped") {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            runCatching { player?.stop(); player?.release() }
            player = null
            runCatching { vibrator?.cancel() }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
            runCatching { ringWakeLock?.let { if (it.isHeld) it.release() } }
            sendRingResult(reason)
        }
    }

    private fun sendRingResult(reason: String) {
        val ip = ringCallbackIp ?: return
        val port = ringCallbackPort
        if (port <= 0) return
        ringCallbackIp = null
        thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 2000)
                val json = JSONObject().put("type", "ring_result").put("result", reason).put("from", Store.activeName())
                val bytes = json.toString().toByteArray()
                val out = s.getOutputStream()
                val payload = ByteArray(bytes.size + 1); payload[0] = 0
                System.arraycopy(bytes, 0, payload, 1, bytes.size)
                out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
                out.write(payload); out.flush(); s.close()
            } catch (e: Exception) { }
        }
    }

    private fun buildServiceNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exit = PendingIntent.getService(this, 1, Intent(this, TacticomService::class.java).setAction("exit"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, App.CH_SERVICE).setContentTitle("TACTICOM").setContentText("Online as ${Store.activeName()}")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setOngoing(true).setContentIntent(open).addAction(android.R.drawable.ic_delete, "Exit", exit).build()
    }

    override fun onDestroy() {
        stopRing("stopped")
        runCatching { cpuLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        NetworkManager.stop()
        instance = null
        super.onDestroy()
    }
}

class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TacticomService.instance?.stopRing("stopped")
    }
}
