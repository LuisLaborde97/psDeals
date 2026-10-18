package com.example.psdeals.data

import android.util.Log
import com.example.psdeals.model.Deal
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit

class DealsRepository(
    private val db: FirebaseFirestore = Firebase.firestore,
) {
    /** Emite la lista completa cada vez que cambia la colección; cancela el listener al dejar de recolectar. */
    fun getDeals(): Flow<List<Deal>> = callbackFlow {
        val registration = db.collection(COLLECTION)
            .orderBy("discountPercent", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val staleBefore = System.currentTimeMillis() - STALE_AFTER_MS
                val deals = snapshot.documents
                    .mapNotNull { it.toDealOrNull() }
                    // El backend no borra las ofertas que terminan; si el scraper lleva días sin verla, ya no está activa.
                    .filter { it.lastSeenAt == null || it.lastSeenAt >= staleBefore }
                trySend(deals)
            }
        awaitClose { registration.remove() }
    }

    /** Mapeo manual y tolerante: un documento mal formado se descarta en vez de romper toda la lista. */
    private fun DocumentSnapshot.toDealOrNull(): Deal? {
        val title = getString("title")
        val productUrl = getString("productUrl")
        if (title.isNullOrBlank() || productUrl.isNullOrBlank()) {
            Log.w(TAG, "Documento $id descartado: falta title o productUrl")
            return null
        }
        return Deal(
            id = id,
            title = title,
            originalPriceText = getString("originalPriceText").orEmpty(),
            discountedPriceText = getString("discountedPriceText").orEmpty(),
            // Firestore puede devolver Long o Double según cómo se escribió el número.
            discountPercent = (get("discountPercent") as? Number)?.toInt() ?: 0,
            imageUrl = getString("imageUrl"),
            productUrl = productUrl,
            lastSeenAt = getTimestamp("lastSeenAt")?.toDate()?.time,
            // Firestore devuelve List<*>: se descarta cualquier elemento que no sea String.
            genres = (get("genres") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        )
    }

    private companion object {
        const val TAG = "DealsRepository"
        const val COLLECTION = "deals"
        // El cron es diario: 3 días de margen tolera un par de ejecuciones fallidas.
        val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(3)
    }
}
