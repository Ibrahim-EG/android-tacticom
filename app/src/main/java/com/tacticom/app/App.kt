package com.tacticom.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
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
        
        // Use the old API 1 way to get the service to avoid verifier crashes
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Compat.createChannels(nm)
        }
    }
}
