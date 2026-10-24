package co.golab.deals.model

/** Oferta tal como la escribe el backend en la colección `deals` de Firestore. */
data class Deal(
    val id: String,
    val title: String,
    /** Precios con moneda, formateados por la PS Store (ej. "US$39.99"). */
    val originalPriceText: String,
    val discountedPriceText: String,
    val discountPercent: Int,
    val imageUrl: String?,
    val productUrl: String,
    /** Última vez que el scraper vio la oferta, en epoch millis. */
    val lastSeenAt: Long?,
    /** Géneros localizados por la tienda (ej. "Acción"); vacío en documentos antiguos. */
    val genres: List<String> = emptyList(),
    /** Id de [StorePlatform]; los documentos anteriores a la migración solo eran de PlayStation. */
    val platform: String = StorePlatform.PLAYSTATION.id,
    /** Enlace de compra común a todas las tiendas; si falta se usa [productUrl]. */
    val storeUrl: String? = null,
    /** Fin de la rebaja (epoch millis) si la tienda lo publica; null si no (ej. PlayStation). */
    val endDate: Long? = null,
    /** false: el backend ya no la vio en una ejecución completa de su tienda; null en documentos antiguos. */
    val active: Boolean? = null,
    /** País de la colección de la que salió (los IDs se repiten entre países). */
    val country: Country = Country.DEFAULT,
) {
    /** Clave en la wishlist del usuario: el ID solo en Colombia, `mx:steam-123` en los demás países. */
    val wishlistKey: String get() = country.wishlistKey(id)
}
