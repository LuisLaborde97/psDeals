package com.example.psdeals.data

import android.util.Log
import com.example.psdeals.model.Deal
import com.example.psdeals.model.StorePlatform
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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

                val now = System.currentTimeMillis()
                trySend(snapshot.documents.mapNotNull { it.toDealOrNull() }.filter { isActive(it, now) })
            }
        awaitClose { registration.remove() }
    }

    /**
     * Ofertas por ID (lista de deseos), en tiempo real. A diferencia de getDeals() no descarta las
     * terminadas: un juego guardado debe seguir en la lista aunque su rebaja acabe (ver [isActive]).
     * Firestore admite hasta 30 valores por whereIn, así que se escucha por lotes y se combinan.
     */
    fun getDealsByIds(ids: Collection<String>): Flow<List<Deal>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        val batches = ids.distinct().chunked(MAX_WHERE_IN).map { batch ->
            callbackFlow {
                val registration = db.collection(COLLECTION)
                    .whereIn(FieldPath.documentId(), batch)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) trySend(snapshot.documents.mapNotNull { it.toDealOrNull() })
                    }
                awaitClose { registration.remove() }
            }
        }
        return combine(batches) { lists -> lists.flatMap { it.asIterable() } }
    }

    /** Mapeo manual y tolerante: un documento mal formado se descarta en vez de romper toda la lista. */
    private fun DocumentSnapshot.toDealOrNull(): Deal? {
        val title = getString("title")
        val storeUrl = getString("storeUrl")?.takeIf { it.isNotBlank() }
        // Las tiendas nuevas pueden traer solo storeUrl: sirve de respaldo para productUrl.
        val productUrl = getString("productUrl")?.takeIf { it.isNotBlank() } ?: storeUrl
        if (title.isNullOrBlank() || productUrl == null) {
            Log.w(TAG, "Documento $id descartado: falta title o enlace a la tienda")
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
            platform = getString("platform")?.takeIf { it.isNotBlank() } ?: StorePlatform.PLAYSTATION.id,
            storeUrl = storeUrl,
        )
    }

    companion object {
        private const val TAG = "DealsRepository"
        private const val COLLECTION = "deals"
        private const val MAX_WHERE_IN = 30
        // El cron es diario: 3 días de margen tolera un par de ejecuciones fallidas.
        private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(3)

        /** El backend no borra las ofertas que terminan: si el scraper lleva días sin verla, ya no está activa. */
        fun isActive(deal: Deal, now: Long = System.currentTimeMillis()): Boolean =
            deal.lastSeenAt == null || deal.lastSeenAt >= now - STALE_AFTER_MS
    }
}
