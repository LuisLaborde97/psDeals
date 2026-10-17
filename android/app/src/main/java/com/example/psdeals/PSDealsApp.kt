package com.example.psdeals

import android.app.Application
import com.example.psdeals.notifications.DealsNotifications

/**
 * El canal se crea aquí y no en MainActivity porque el servicio FCM puede arrancar
 * (y recibir un push) sin que ninguna Activity se haya abierto.
 */
class PSDealsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DealsNotifications.createChannel(this)
        DealsNotifications.subscribeToTopic()
    }
}
