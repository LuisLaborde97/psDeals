package com.example.psdeals.notifications

import android.content.Intent
import androidx.core.net.toUri

/**
 * Lee el toque en una notificación. Los campos del bloque `data` del push llegan como extras del
 * intent de MainActivity: los pone FCM si mostró la notificación (app en segundo plano) o
 * DealsNotifications.show() si la mostró la app (primer plano).
 */
object NotificationTap {
    // Claves y valores que envía el backend (notify.ts y wishlistAlerts.ts).
    const val KEY_TYPE = "type"
    const val TYPE_WISHLIST_ALERT = "wishlist_alert"
    private const val TYPE_DEAL = "deal"
    private const val KEY_STORE_URL = "storeUrl"
    private const val KEY_PRODUCT_URL = "productUrl"

    /** @param openWishlist al volver de la tienda, el usuario debe quedar en la pestaña Favoritos. */
    data class OpenStore(val url: String, val openWishlist: Boolean)

    fun parse(intent: Intent): OpenStore? {
        val extras = intent.extras ?: return null
        val type = extras.getString(KEY_TYPE) ?: return null
        val url = (extras.getString(KEY_STORE_URL) ?: extras.getString(KEY_PRODUCT_URL))
            // Solo enlaces web: un dato del push nunca debe poder lanzar un intent: o un esquema arbitrario.
            ?.takeIf { it.toUri().scheme in setOf("https", "http") }
            ?: return null
        return when (type) {
            TYPE_WISHLIST_ALERT -> OpenStore(url, openWishlist = true)
            // Push del topic general: también promete una oferta concreta.
            TYPE_DEAL -> OpenStore(url, openWishlist = false)
            else -> null
        }
    }

    /** Copia del intent sin la marca del push: una vez manejado, ningún relanzamiento lo vuelve a disparar. */
    fun consumed(intent: Intent): Intent = Intent(intent).apply { removeExtra(KEY_TYPE) }
}
