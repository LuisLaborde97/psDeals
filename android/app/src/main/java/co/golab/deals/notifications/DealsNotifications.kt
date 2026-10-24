package co.golab.deals.notifications

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
import androidx.core.content.edit
import co.golab.deals.MainActivity
import co.golab.deals.R
import co.golab.deals.data.AppPreferences
import co.golab.deals.data.StorePreferences
import co.golab.deals.model.Country
import co.golab.deals.model.StorePlatform
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging

object DealsNotifications {
    private const val TAG = "DealsNotifications"

    /** Deben coincidir con el `channelId` que envía el backend (notify.ts y wishlistAlerts.ts). */
    const val CHANNEL_ID = "daily_deals"
    const val WISHLIST_CHANNEL_ID = "wishlist_alerts"
    /** Canal general de las versiones anteriores: las nuevas se salen y usan los temas por tienda ([syncTopics]). */
    private const val LEGACY_TOPIC = "daily_deals"
    private const val TOPIC_PREFS = "fcm_topics"
    private const val KEY_SYNCED = "synced"

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
     * Deja este teléfono suscrito solo al tema de cada tienda elegida con su umbral (p. ej. `deals_co_xbox_50`) y fuera
     * del resto y del canal general. Las operaciones (una por tema) solo se repiten si la elección cambió, si FCM rotó el token
     * ([force]) o si un intento anterior falló (sin red): la marca se guarda cuando todas terminan bien.
     */
    fun syncTopics(context: Context, preferences: StorePreferences, force: Boolean = false) {
        val desired = preferences.alertStores.map { AppPreferences.topicFor(preferences.country, it, preferences.minDiscount) }.toSortedSet()
        val signature = desired.joinToString(",")
        val prefs = context.getSharedPreferences(TOPIC_PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SYNCED, null) == signature) return
        val messaging = Firebase.messaging
        // Todos los países: al cambiar de país también hay que salir de los temas del anterior.
        val all = Country.entries.flatMap { c ->
            StorePlatform.entries.flatMap { p -> AppPreferences.DISCOUNT_OPTIONS.map { AppPreferences.topicFor(c, p, it) } }
        }
        val tasks = all.map { topic -> if (topic in desired) messaging.subscribeToTopic(topic) else messaging.unsubscribeFromTopic(topic) } +
            messaging.unsubscribeFromTopic(LEGACY_TOPIC)
        Tasks.whenAll(tasks).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                prefs.edit { putString(KEY_SYNCED, signature) }
                Log.d(TAG, "Temas de ofertas sincronizados: ${signature.ifEmpty { "ninguno" }}")
            } else {
                // Se reintenta en el próximo arranque: sin marca guardada, la firma no coincide.
                prefs.edit { remove(KEY_SYNCED) }
                Log.w(TAG, "No se pudieron sincronizar los temas de ofertas", task.exception)
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
