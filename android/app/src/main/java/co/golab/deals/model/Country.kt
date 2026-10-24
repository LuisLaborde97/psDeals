package co.golab.deals.model

/**
 * Países con ofertas. Debe coincidir con backend/src/country.ts: Colombia conserva los nombres de antes (colección
 * `deals`, `config/scraper`, IDs de wishlist sin prefijo) para que las versiones anteriores a los países sigan
 * funcionando; los demás llevan sufijo (`deals_mx`) o prefijo (`mx:steam-123`).
 *
 * @param code código ISO en minúsculas, el mismo que usa el backend.
 */
enum class Country(val code: String, val label: String, val flag: String) {
    COLOMBIA("co", "Colombia", "🇨🇴"),
    MEXICO("mx", "México", "🇲🇽"),
    ;

    val isLegacy: Boolean get() = this == COLOMBIA

    /** Colección de Firestore con sus ofertas. */
    val dealsCollection: String get() = if (isLegacy) "deals" else "deals_$code"

    /** Documento de `config` del país: `scraper` / `scraper_mx`. */
    fun configDoc(base: String): String = if (isLegacy) base else "${base}_$code"

    /** Clave de una oferta de este país en la wishlist del usuario. */
    fun wishlistKey(dealId: String): String = if (isLegacy) dealId else "$code:$dealId"

    companion object {
        val DEFAULT = COLOMBIA

        fun fromCode(code: String?): Country? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        /** "mx:steam-123" → México y "steam-123"; sin prefijo, Colombia (las claves anteriores a los países). */
        fun parseWishlistKey(key: String): Pair<Country, String> {
            val prefix = key.substringBefore(':', missingDelimiterValue = "")
            val country = fromCode(prefix)
            return if (country != null) country to key.substringAfter(':') else COLOMBIA to key
        }
    }
}
