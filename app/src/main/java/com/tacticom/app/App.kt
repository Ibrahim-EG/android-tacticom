package com.tacticom.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context

class App : Application() {
    companion object {
        const val CH_SERVICE = "service"
        const val CH_RING = "ring"
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Bus.dark.value = Store.themeDark
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Use reflection to create channels so Android 7 doesn't crash on missing classes
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                val channelClass = Class.forName("android.app.NotificationChannel")
                val constructor = channelClass.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
                val createMethod = NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
                
                val ch1 = constructor.newInstance(CH_SERVICE, "Intercom Service", NotificationManager.IMPORTANCE_LOW)
                createMethod.invoke(nm, ch1)
                
                val ch2 = constructor.newInstance(CH_RING, "Incoming Ring", NotificationManager.IMPORTANCE_HIGH)
                createMethod.invoke(nm, ch2)
            } catch (e: Exception) {
                // Silently fail on Android 7 or if reflection breaks
            }
        }
    }
}
