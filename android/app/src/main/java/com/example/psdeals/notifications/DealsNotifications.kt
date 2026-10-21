package com.example.psdeals.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.psdeals.MainActivity
import com.example.psdeals.R
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging

object DealsNotifications {
    private const val TAG = "DealsNotifications"

    /** Deben coincidir con el `channelId` que envía el backend (notify.ts y wishlistAlerts.ts). */
    const val CHANNEL_ID = "daily_deals"
    const val WISHLIST_CHANNEL_ID = "wishlist_alerts"
    const val TOPIC = "daily_deals"

    /**
     * Idempotente: crear un canal que ya existe no hace nada. NotificationManagerCompat ignora API < 26.
     * Canales separados para que el usuario pueda silenciar las ofertas del día sin perder las alertas
     * de su lista de deseos (Ajustes → Notificaciones de la app).
     */
    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_daily_deals_name))
                .setDescription(context.getString(R.string.channel_daily_deals_description))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(WISHLIST_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_wishlist_alerts_name))
                .setDescription(context.getString(R.string.channel_wishlist_alerts_description))
                .build(),
        )
    }

    /**
     * Se llama en cada arranque y en cada token nuevo: es idempotente y repara la
     * suscripción si un intento anterior falló (sin red) o si FCM rotó el token.
     */
    fun subscribeToTopic() {
        Firebase.messaging.subscribeToTopic(TOPIC).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Suscrito al topic $TOPIC")
            } else {
                Log.w(TAG, "No se pudo suscribir al topic $TOPIC", task.exception)
            }
        }
    }

    /**
     * Muestra un push recibido con la app en primer plano (FCM no lo muestra solo en ese caso).
     *
     * @param data el bloque `data` del push: va como extras del intent, igual que cuando FCM muestra la
     *   notificación en segundo plano, para que MainActivity maneje el toque de la misma forma.
     */
    fun show(context: Context, title: String?, body: String?, tag: String, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Sin permiso POST_NOTIFICATIONS; push no mostrado")
            return
        }

        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        data.forEach { (key, value) -> launch.putExtra(key, value) }
        val openApp = PendingIntent.getActivity(
            context,
            // requestCode distinto por notificación: con uno fijo, FLAG_UPDATE_CURRENT reemplazaría los extras
            // de las notificaciones anteriores y todas abrirían el último juego.
            tag.hashCode(),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (data[NotificationTap.KEY_TYPE] == NotificationTap.TYPE_WISHLIST_ALERT) WISHLIST_CHANNEL_ID else CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        // Mismo tag que usa el backend en `android.notification.tag`: una oferta = una notificación.
        NotificationManagerCompat.from(context).notify(tag, 0, notification)
    }
}
