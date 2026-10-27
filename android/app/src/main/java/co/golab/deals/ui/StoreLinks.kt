package co.golab.deals.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

/** Política de privacidad pública (la misma URL declarada en Play Console). */
const val PRIVACY_POLICY_URL = "https://github.com/LuisLaborde97/psDeals/blob/main/PRIVACY_POLICY.md"

/** Abre la app nativa de la tienda si está instalada y reclama el dominio (App Links); si no, el navegador. */
fun Context.openStoreUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Log.w("StoreLinks", "No hay app para abrir $url", e)
    }
}
