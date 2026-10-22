package com.example.psdeals.data

import android.util.Log
import com.example.psdeals.model.Deal
import com.example.psdeals.model.StorePlatform
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.TimeUnit

/**
 * Ofertas vigentes para mostrar.
 * @param isFromCache Firestore no pudo llegar al servidor y sirvió lo guardado en el teléfono: precios y
 *   vigencia pueden estar desactualizados.
 */
data class DealsFeed(val deals: List<Deal>, val isFromCache: Boolean)

class DealsRepository(
    private val db: FirebaseFirestore = Firebase.firestore,
) {
    /**
     * Ofertas vigentes en tiempo real. Un listener ya prioriza el servidor (Source.DEFAULT es para get()):
     * la caché solo se usa sin conexión, y entonces [DealsFeed.isFromCache] lo avisa. La vigencia se
     * recalcula cada minuto para que una rebaja que vence con la app abierta desaparezca sola.
     */
    fun getDeals(): Flow<DealsFeed> = combine(observeAllDeals(), observeLastRuns(), minuteTicker()) { snapshot, lastRuns, now ->
        DealsFeed(snapshot.deals.filter { isActive(it, now, lastRuns[it.platform]) }, snapshot.isFromCache)
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

    /**
     * `config/scraper.lastRunAt`: cuándo terminó el backend la última ejecución completa de cada tienda
     * (id de plataforma → epoch millis). Vacío si no existe o no se puede leer: se cae a la regla de 3 días.
     */
    fun observeLastRuns(): Flow<Map<String, Long>> = callbackFlow {
        val registration = db.collection(CONFIG).document(SCRAPER_DOC).addSnapshotListener { snapshot, error ->
            if (error != null) Log.w(TAG, "No se pudo leer config/$SCRAPER_DOC", error)
            val runs = (snapshot?.get("lastRunAt") as? Map<*, *>).orEmpty()
                .mapNotNull { (platform, at) -> (platform as? String)?.let { p -> (at as? Timestamp)?.let { p to it.toDate().time } } }
                .toMap()
            trySend(runs)
        }
        awaitClose { registration.remove() }
    }

    private data class Snapshot(val deals: List<Deal>, val isFromCache: Boolean)

    private fun observeAllDeals(): Flow<Snapshot> = callbackFlow {
        // INCLUDE: avisa también cuando cambia solo isFromCache (ej. vuelve la conexión con los mismos datos).
        val registration = db.collection(COLLECTION)
            .orderBy("discountPercent", Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                trySend(Snapshot(snapshot.documents.mapNotNull { it.toDealOrNull() }, snapshot.metadata.isFromCache))
            }
        awaitClose { registration.remove() }
    }

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
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
            // El backend la escribe en epoch millis; null si la tienda no la publica.
            endDate = (get("endDate") as? Number)?.toLong(),
        )
    }

    companion object {
        private const val TAG = "DealsRepository"
        private const val COLLECTION = "deals"
        private const val CONFIG = "config"
        private const val SCRAPER_DOC = "scraper"
        private const val MAX_WHERE_IN = 30
        private val TICK_MS = TimeUnit.MINUTES.toMillis(1)
        // Respaldo si una tienda deja de ejecutarse: el cron es diario, 3 días toleran un par de fallos.
        private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(3)
        // Las ofertas de una ejecución se escriben en lotes antes de marcar lastRunAt: margen de sobra.
        private val RUN_MARGIN_MS = TimeUnit.HOURS.toMillis(1)

        /**
         * El backend no borra las ofertas que terminan, así que la app decide si siguen vigentes:
         * 1. su fecha de fin (si la tienda la publica) no pasó;
         * 2. apareció en la última ejecución completa de su tienda ([lastRunAt]): si no apareció, la
         *    tienda ya no la ofrece (antes seguía visible hasta 3 días);
         * 3. el scraper la vio en los últimos 3 días (respaldo si una tienda deja de ejecutarse).
         */
        fun isActive(deal: Deal, now: Long = System.currentTimeMillis(), lastRunAt: Long? = null): Boolean {
            if (deal.endDate != null && deal.endDate <= now) return false
            val seen = deal.lastSeenAt ?: return true // documentos antiguos sin el campo
            if (lastRunAt != null && seen < lastRunAt - RUN_MARGIN_MS) return false
            return seen >= now - STALE_AFTER_MS
        }
    }
}
