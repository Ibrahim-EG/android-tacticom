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

class TacticomService : Service() {

    companion object {
        @Volatile var instance: TacticomService? = null
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastRestart = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        Controller.appContext = applicationContext
        Controller.initAudio()
        
        Thread { NetworkManager.start(this) }.start()
        
        registerNetworkCallback()
        Bus.status.value = "ONLINE"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "exit") {
            stopRing()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val notif = buildServiceNotification()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (micGranted) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(1, notif, types)
        } else {
            startForeground(1, notif)
        }
        return START_STICKY
    }

    private fun registerNetworkCallback() {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { restartNetwork() }
            override fun onLost(network: Network) { restartNetwork() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { restartNetwork() }
        })
    }

    private fun restartNetwork() {
        val now = System.currentTimeMillis()
        if (now - lastRestart < 3000) return
        lastRestart = now
        NetworkManager.stop()
        Thread { NetworkManager.start(this) }.start()
    }

    fun startRing(from: String) {
        if (player != null) return
        handler.post {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tacticom:ring")
                .apply { acquire(20_000) }

            val uriStr = Store.activeProfile().ringtone
            val uri: Uri? = uriStr?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = uri?.let { MediaPlayer.create(this@TacticomService, it) }?.apply {
                isLooping = true
                start()
            }

            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val effectClass = Class.forName("android.os.VibrationEffect")
                    val createMethod = effectClass.getMethod("createWaveform", LongArray::class.java, Int::class.javaPrimitiveType)
                    val effect = createMethod.invoke(null, longArrayOf(0, 800, 400), 0)
                    val vibrateMethod = Vibrator::class.java.getMethod("vibrate", effectClass)
                    vibrateMethod.invoke(vibrator, effect)
                } catch (e: Exception) {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 800, 400), 0)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 800, 400), 0)
            }

            val stopIntent = PendingIntent.getBroadcast(
                this, 2, Intent(this, RingStopReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, App.CH_RING)
                .setContentTitle("TACTICOM — Incoming Ring")
                .setContentText("$from wants your attention")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .addAction(android.R.drawable.ic_delete, "STOP RINGING", stopIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(2, notif)
            handler.postDelayed({ stopRing() }, 15_000)
        }
    }

    fun stopRing() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(2)
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = PendingIntent.getService(
            this, 1, Intent(this, TacticomService::class.java).setAction("exit"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CH_SERVICE)
            .setContentTitle("TACTICOM")
            .setContentText("Intercom online as ${Store.activeName()}")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Exit", exitIntent)
            .build()
    }

    override fun onDestroy() {
        stopRing()
        NetworkManager.stop()
        instance = null
        super.onDestroy()
    }
}

class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TacticomService.instance?.stopRing()
    }
}
