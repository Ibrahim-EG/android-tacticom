package com.tacticom.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    companion object {
        const val CH_SERVICE = "service"
        const val CH_RING = "ring"
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Bus.dark.value = Store.themeDark
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Intercom Service", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_RING, "Incoming Ring", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
