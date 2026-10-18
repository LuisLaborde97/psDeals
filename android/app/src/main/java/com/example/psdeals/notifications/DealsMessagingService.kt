package com.example.psdeals.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * - App en segundo plano: FCM muestra la notificación solo, en el canal `daily_deals`,
 *   y este método NO se llama.
 * - App en primer plano: FCM entrega el mensaje aquí y no muestra nada, por eso lo mostramos.
 */
class DealsMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        Log.d(TAG, "Push de ${message.from}: title=${notification?.title} data=${message.data}")

        if (notification != null) {
            val tag = message.data["dealId"] ?: message.data["type"] ?: message.messageId ?: "push"
            DealsNotifications.show(this, notification.title, notification.body, tag)
        }
    }

    /** Ruta nueva de firebase-messaging 26+ (acción FCM_REGISTERED). */
    override fun onRegistered(token: String) {
        // No se registra el token en el log: identifica al dispositivo.
        Log.d(TAG, "Registro FCM actualizado; re-suscribiendo al topic")
        DealsNotifications.subscribeToTopic()
    }

    /** Ruta antigua (acción NEW_TOKEN), obsoleta pero aún despachada por el SDK; la suscripción es idempotente. */
    @Deprecated("Reemplazado por onRegistered en firebase-messaging 26")
    override fun onNewToken(token: String) {
        Log.d(TAG, "Token FCM renovado; re-suscribiendo al topic")
        DealsNotifications.subscribeToTopic()
    }

    private companion object {
        const val TAG = "DealsMessagingService"
    }
}
