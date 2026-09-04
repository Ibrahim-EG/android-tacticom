package com.tacticom.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi

/** 
 * This file hides all Android 8+ (API 26+) classes from the Android 7 class verifier.
 * If these were in App.kt or TacticomService.kt, Android 7 would crash on launch.
 */
object Compat {
    
    @RequiresApi(Build.VERSION_CODES.O)
    fun createChannels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(App.CH_SERVICE, "Intercom Service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(App.CH_RING, "Incoming Ring", NotificationManager.IMPORTANCE_HIGH))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun vibrate(vibrator: Vibrator?) {
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    fun startServiceSafe(c: Context, i: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i)
        } else {
            c.startService(i)
        }
    }
}
