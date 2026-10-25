package co.golab.deals.data

import android.util.Log
import co.golab.deals.BuildConfig
import co.golab.deals.model.Country
import co.golab.deals.model.Deal
import co.golab.deals.model.StorePlatform
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Filtros de la lista principal. La base de la consulta (where + orderBy) sale de aquí y es la misma en
 * todas las páginas; limit y startAfter solo la recorren.
 * @param platforms ids de [StorePlatform]; vacío = todas.
 * @param genres OR entre géneros; vacío = sin filtro.
 * @param freeOnly solo juegos gratis (100 % de descuento).
 * @param endingSoon solo ofertas que terminan en las próximas 24 h, la más próxima primero.
 * @param country país de las ofertas: cada uno tiene su colección (Country.dealsCollection).
 */
data class DealsQuery(
    val country: Country = Country.DEFAULT,
    val platforms: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val search: String = "",
    val freeOnly: Boolean = false,
    val endingSoon: Boolean = false,
) {
    val isFiltered: Boolean
        get() = genres.isNotEmpty() || freeOnly || endingSoon || DealsRepository.searchWords(search).isNotEmpty()
}

/**
 * Una página de ofertas, ya filtrada por [DealsQuery] (aún sin las reglas de vigencia: ver [DealsRepository.isActive]).
 * @param cursor último documento leído, para pedir la siguiente con startAfter; null si la página vino vacía.
 * @param isFromCache Firestore no pudo llegar al servidor y sirvió lo guardado en el teléfono: precios y
 *   vigencia pueden estar desactualizados.
 */
data class DealsPage(
    val deals: List<Deal>,
    val cursor: DocumentSnapshot?,
    val isLastPage: Boolean,
    val isFromCache: Boolean,
)

class DealsRepository(
    private val db: FirebaseFirestore = Firebase.firestore,
) {
    /**
     * Una página de [pageSize] ofertas, de mayor a menor descuento, después de [after] (null = la primera).
     * get() y no un listener: un listener sobre la colección descargaba las 3.000 ofertas en cada apertura, y
     * los precios cambian una vez al día. Source.DEFAULT pide al servidor y, sin conexión, responde con la
     * caché ([DealsPage.isFromCache]).
     */
    suspend fun getDealsPage(query: DealsQuery, after: DocumentSnapshot? = null, pageSize: Long = PAGE_SIZE): DealsPage {
        val plan = plan(query)
        val firestoreQuery = plan.query.limit(pageSize).let { if (after != null) it.startAfter(after) else it }
        val snapshot = firestoreQuery.get(Source.DEFAULT).await()
        return DealsPage(
            deals = snapshot.documents.mapNotNull { it.toDealOrNull(query.country) }.filter(plan.matches).filter(::isAllowed),
            cursor = snapshot.documents.lastOrNull() ?: after,
            isLastPage = snapshot.size() < pageSize,
            isFromCache = snapshot.metadata.isFromCache,
        )
    }

    /**
     * Cuántas ofertas trae [query], contadas en el servidor (count() cobra 1 lectura por cada 1.000). null sin
     * conexión, o si parte del filtro corre en el teléfono y el conteo del servidor no sería exacto.
     */
    suspend fun countDeals(query: DealsQuery): Int? {
        val plan = plan(query)
        if (plan.hasClientFilter) return null
        return try {
            plan.query.count().get(AggregateSource.SERVER).await().count.toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron contar las ofertas", e)
            null
        }
    }

    /**
     * `config/genres.byPlatform`: géneros de las ofertas de cada tienda (id de plataforma → géneros), escritos
     * por el backend. Los chips ya no pueden salir de la lista, que llega por páginas.
     */
    fun observeGenres(country: Country): Flow<Map<String, List<String>>> = callbackFlow {
        val docId = country.configDoc(GENRES_DOC)
        val registration = db.collection(CONFIG).document(docId).addSnapshotListener { snapshot, error ->
            if (error != null) Log.w(TAG, "No se pudo leer config/$docId", error)
            val genres = (snapshot?.get("byPlatform") as? Map<*, *>).orEmpty()
                .mapNotNull { (platform, list) ->
                    (platform as? String)?.let { p -> p to (list as? List<*>)?.filterIsInstance<String>().orEmpty() }
                }
                .toMap()
            trySend(genres)
        }
        awaitClose { registration.remove() }
    }

    /**
     * Ofertas por ID (lista de deseos), en tiempo real. A diferencia de getDeals() no descarta las
     * terminadas: un juego guardado debe seguir en la lista aunque su rebaja acabe (ver [isActive]).
     * Firestore admite hasta 30 valores por whereIn, así que se escucha por lotes y se combinan.
     *
     * @param keys claves de la wishlist ([Deal.wishlistKey]): cada una se busca en la colección de su país.
     */
    fun getDealsByIds(keys: Collection<String>): Flow<List<Deal>> {
        if (keys.isEmpty()) return flowOf(emptyList())
        val byCountry = keys.distinct().map(Country::parseWishlistKey).groupBy({ it.first }, { it.second })
        val batches = byCountry.flatMap { (country, ids) ->
            ids.chunked(MAX_WHERE_IN).map { batch ->
                callbackFlow {
                    val registration = db.collection(country.dealsCollection)
                        .whereIn(FieldPath.documentId(), batch)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                close(error)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) trySend(snapshot.documents.mapNotNull { it.toDealOrNull(country) }.filter(::isAllowed))
                        }
                    awaitClose { registration.remove() }
                }
            }
        }
        return combine(batches) { lists -> lists.flatMap { it.asIterable() } }
    }

    /**
     * `config/scraper.lastRunAt`: cuándo terminó el backend la última ejecución completa de cada tienda
     * (id de plataforma → epoch millis). Vacío si no existe o no se puede leer: se cae a la regla de 3 días.
     */
    fun observeLastRuns(country: Country): Flow<Map<String, Long>> = callbackFlow {
        val docId = country.configDoc(SCRAPER_DOC)
        val registration = db.collection(CONFIG).document(docId).addSnapshotListener { snapshot, error ->
            if (error != null) Log.w(TAG, "No se pudo leer config/$docId", error)
            val runs = (snapshot?.get("lastRunAt") as? Map<*, *>).orEmpty()
                .mapNotNull { (platform, at) -> (platform as? String)?.let { p -> (at as? Timestamp)?.let { p to it.toDate().time } } }
                .toMap()
            trySend(runs)
        }
        awaitClose { registration.remove() }
    }

    /**
     * Consulta de Firestore para [query] y la parte del filtro que no puede ir en ella ([matches]).
     * Firestore admite un solo filtro de arreglo por consulta y hasta 30 combinaciones de in × array-contains-any:
     * con búsqueda, el servidor filtra por su palabra más larga (la más selectiva) y los géneros y las demás
     * palabras se revisan en el teléfono; sin búsqueda, los géneros van al servidor si caben.
     * Cada combinación necesita su índice compuesto (firestore.indexes.json).
     */
    private fun plan(query: DealsQuery): QueryPlan {
        var firestoreQuery: Query = db.collection(query.country.dealsCollection).whereEqualTo("active", true)
        if (query.platforms.isNotEmpty()) firestoreQuery = firestoreQuery.whereIn("platform", query.platforms.toList())
        val words = searchWords(query.search)
        if (query.freeOnly || query.endingSoon) return specialPlan(firestoreQuery, query, words)
        val serverWord = words.maxByOrNull { it.length }?.take(MAX_TOKEN_LENGTH)
        val genresOnServer = serverWord == null && query.genres.isNotEmpty() &&
            query.genres.size * max(1, query.platforms.size) <= MAX_DISJUNCTIONS
        if (serverWord != null) {
            firestoreQuery = firestoreQuery.whereArrayContains("searchTokens", serverWord)
        } else if (genresOnServer) {
            firestoreQuery = firestoreQuery.whereArrayContainsAny("genres", query.genres.toList())
        }
        val genresOnClient = query.genres.isNotEmpty() && !genresOnServer
        val wordsOnClient = words.size > 1 || words.any { it.length > MAX_TOKEN_LENGTH }
        return QueryPlan(
            query = firestoreQuery.orderBy("discountPercent", Query.Direction.DESCENDING),
            hasClientFilter = genresOnClient || wordsOnClient,
        ) { deal ->
            (!genresOnClient || deal.genres.any { it in query.genres }) && (!wordsOnClient || matchesWords(deal, words))
        }
    }

    /**
     * "Gratis" y "Termina pronto" dan conjuntos chicos (decenas de ofertas): el servidor aplica solo su condición y
     * géneros y búsqueda se revisan en el teléfono. Así no hace falta un índice compuesto por cada combinación.
     * "Termina pronto" necesita los índices (active, endDate) y (active, platform, endDate) de firestore.indexes.json.
     */
    private fun specialPlan(base: Query, query: DealsQuery, words: List<String>): QueryPlan {
        val firestoreQuery = if (query.endingSoon) {
            val now = System.currentTimeMillis()
            base.whereGreaterThan("endDate", now)
                .whereLessThanOrEqualTo("endDate", now + ENDING_SOON_MS)
                .orderBy("endDate", Query.Direction.ASCENDING)
        } else {
            // Sin orderBy: Firestore no ordena por un campo filtrado con igualdad (todas son 100 %).
            base.whereEqualTo("discountPercent", FREE_DISCOUNT)
        }
        val freeOnClient = query.freeOnly && query.endingSoon
        return QueryPlan(
            query = firestoreQuery,
            hasClientFilter = freeOnClient || query.genres.isNotEmpty() || words.isNotEmpty(),
        ) { deal ->
            (!freeOnClient || deal.discountPercent >= FREE_DISCOUNT) &&
                (query.genres.isEmpty() || deal.genres.any { it in query.genres }) &&
                matchesWords(deal, words)
        }
    }

    /**
     * La variante de Google Play no muestra contenido explícito (su política no lo permite). Se filtra en el teléfono
     * y no en la consulta: son muy pocas ofertas y así no hace falta duplicar cada índice compuesto. Por eso el
     * contador del servidor puede incluir alguna de más en esa variante.
     */
    private fun isAllowed(deal: Deal): Boolean = !(BuildConfig.HIDE_EXPLICIT && deal.explicit)

    /** Cada palabra buscada es el comienzo de alguna palabra del título (misma regla que searchTokens en el backend). */
    private fun matchesWords(deal: Deal, words: List<String>): Boolean =
        words.isEmpty() || searchWords(deal.title).let { title -> words.all { w -> title.any { it.startsWith(w) } } }

    private class QueryPlan(val query: Query, val hasClientFilter: Boolean, val matches: (Deal) -> Boolean)

    /** Hora actual cada minuto: la vigencia se recalcula para que una rebaja que vence con la app abierta desaparezca sola. */
    fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    /** Mapeo manual y tolerante: un documento mal formado se descarta en vez de romper toda la lista. */
    private fun DocumentSnapshot.toDealOrNull(country: Country): Deal? {
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
            active = getBoolean("active"),
            country = country,
            explicit = getBoolean("explicit") == true,
        )
    }

    companion object {
        private const val TAG = "DealsRepository"
        private const val CONFIG = "config"
        private const val SCRAPER_DOC = "scraper"
        private const val GENRES_DOC = "genres"
        const val PAGE_SIZE = 50L
        /** Igual que en el backend (searchTokens): prefijos de hasta 15 letras por palabra. */
        private const val MAX_TOKEN_LENGTH = 15
        private const val MAX_DISJUNCTIONS = 30
        private const val FREE_DISCOUNT = 100
        /** Ventana de "Termina pronto". */
        val ENDING_SOON_MS = TimeUnit.HOURS.toMillis(24)
        private const val MAX_WHERE_IN = 30
        private val TICK_MS = TimeUnit.MINUTES.toMillis(1)
        // Respaldo si una tienda deja de ejecutarse: el cron es diario, 3 días toleran un par de fallos.
        private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(3)
        // Las ofertas de una ejecución se escriben en lotes antes de marcar lastRunAt: margen de sobra.
        private val RUN_MARGIN_MS = TimeUnit.HOURS.toMillis(1)

        /**
         * El backend no borra las ofertas que terminan, así que la app decide si siguen vigentes:
         * 0. el backend no la marcó inactiva (dejó de verla en una ejecución completa);
         * 1. su fecha de fin (si la tienda la publica) no pasó;
         * 2. apareció en la última ejecución completa de su tienda ([lastRunAt]): si no apareció, la
         *    tienda ya no la ofrece (antes seguía visible hasta 3 días);
         * 3. el scraper la vio en los últimos 3 días (respaldo si una tienda deja de ejecutarse).
         */
        fun isActive(deal: Deal, now: Long = System.currentTimeMillis(), lastRunAt: Long? = null): Boolean {
            if (deal.active == false) return false
            if (deal.endDate != null && deal.endDate <= now) return false
            val seen = deal.lastSeenAt ?: return true // documentos antiguos sin el campo
            if (lastRunAt != null && seen < lastRunAt - RUN_MARGIN_MS) return false
            return seen >= now - STALE_AFTER_MS
        }

        /**
         * Palabras normalizadas igual que el backend al escribir searchTokens: minúsculas, sin tildes ni
         * apóstrofos ("Marvel's Pokémon" → marvels, pokemon). Una búsqueda encuentra un título si cada una de
         * sus palabras es el comienzo de alguna palabra del título.
         */
        fun searchWords(text: String): List<String> =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .replace(APOSTROPHES, "")
                .split(NON_WORD)
                .filter { it.isNotEmpty() }

        private val DIACRITICS = "\\p{Mn}+".toRegex()
        private val APOSTROPHES = "['’]".toRegex()
        private val NON_WORD = "[^\\p{L}\\p{N}]+".toRegex()
    }
}
