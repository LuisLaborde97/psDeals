package com.example.psdeals.notifications

import android.util.Log
import com.example.psdeals.PSDealsApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * - App en segundo plano: FCM muestra la notificación solo, en el canal que indique el backend
 *   (`daily_deals` o `wishlist_alerts`), y este método NO se llama.
 * - App en primer plano: FCM entrega el mensaje aquí y no muestra nada, por eso lo mostramos.
 */
class DealsMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        Log.d(TAG, "Push de ${message.from}: title=${notification?.title} data=${message.data}")

        if (notification != null) {
            val tag = message.data["dealId"] ?: message.data["type"] ?: message.messageId ?: "push"
            DealsNotifications.show(this, notification.title, notification.body, tag, message.data)
        }
    }

    /**
     * Modelo de envío por Firebase Installation ID (firebase-messaging 26+, opt-in con
     * `firebase_messaging_installation_id_enabled`). No está activado, así que no se llama; si algún día
     * se activa, recibe un FID y no un token: por eso solo repara el topic y no toca fcmTokens.
     */
    override fun onRegistered(installationId: String) {
        Log.d(TAG, "Registro FCM (FID) actualizado; re-suscribiendo al topic")
        DealsNotifications.subscribeToTopic()
    }

    /**
     * Token nuevo (instalación, restauración o rotación). Marcado obsoleto junto con getToken(), pero es la
     * ruta del modelo por token, que es el que usa el backend.
     */
    @Deprecated("Ruta del modelo por token; ver onRegistered")
    override fun onNewToken(token: String) {
        // No se registra el token en el log: identifica al dispositivo.
        Log.d(TAG, "Token FCM renovado; re-suscribiendo al topic y actualizando el perfil")
        DealsNotifications.subscribeToTopic()
        (application as PSDealsApp).onNewPushToken(token)
    }

    private companion object {
        const val TAG = "DealsMessagingService"
    }
}
