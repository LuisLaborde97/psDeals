package co.golab.deals.data

import android.content.Context
import android.telephony.TelephonyManager
import co.golab.deals.model.Country
import java.util.Locale

/**
 * País sugerido al abrir la app por primera vez. Es solo un punto de partida: los precios dependen del país de la
 * cuenta del usuario en cada tienda, no de dónde esté, así que siempre se puede cambiar en Ajustes.
 * Sin permisos: SIM (dónde se contrató la línea) → red móvil → región del teléfono.
 */
object CountryDetector {
    /** Código ISO detectado, aunque la app aún no tenga ofertas para ese país; null si no hay ninguna señal. */
    fun detectIso(context: Context): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.trim().lowercase() }
            .firstOrNull { it.length == 2 }
    }

    /** El país detectado si ya tiene ofertas; si no, Colombia. */
    fun detect(context: Context): Country = Country.fromCode(detectIso(context)) ?: Country.DEFAULT

    /** Nombre del país en español ("Perú") para avisar que aún no tiene ofertas. */
    fun displayName(iso: String): String =
        runCatching { Locale.Builder().setRegion(iso.uppercase()).build().getDisplayCountry(Locale.forLanguageTag("es")) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: iso.uppercase()
}
