package com.example.psdeals.model

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
)
