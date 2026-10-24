package co.golab.deals.ui.deals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.golab.deals.PSDealsApp
import co.golab.deals.data.AppPreferences
import co.golab.deals.data.DealsPage
import co.golab.deals.data.DealsQuery
import co.golab.deals.data.DealsRepository
import co.golab.deals.data.StorePreferences
import co.golab.deals.model.Country
import co.golab.deals.model.Deal
import co.golab.deals.model.StorePlatform
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator

sealed interface DealsUiState {
    data object Loading : DealsUiState

    /**
     * @param deals ofertas cargadas hasta ahora (páginas acumuladas), ya filtradas por tienda, búsqueda y género.
     * @param totalCount ofertas de las tiendas elegidas (o de todas), contadas en el servidor; null si no se pudo.
     * @param filteredCount ofertas que cumplen búsqueda y géneros; null si no se pudo contar (ver
     *   DealsRepository.countDeals) o si no hay filtros.
     * @param isFiltered hay búsqueda o género activos (distingue "sin ofertas" de "sin coincidencias").
     * @param query filtros de los que salen [deals]. Viaja con los datos y no aparte para que la UI sepa
     *   exactamente cuándo llegó la lista de la selección nueva.
     * @param isInitialLoading se está pidiendo la primera página de otros filtros (o de un refresco): [deals]
     *   sigue mostrando la lista anterior hasta que llegue.
     * @param isLoadingMore se está pidiendo la página siguiente.
     * @param isLastPage ya se cargaron todas las ofertas de [query].
     * @param loadMoreFailed falló la última página pedida: la UI ofrece reintentar en vez de pedirla sola.
     */
    data class Success(
        val deals: List<Deal>,
        val totalCount: Int?,
        val filteredCount: Int?,
        val isFiltered: Boolean,
        val query: DealsQuery,
        val isInitialLoading: Boolean,
        val isLoadingMore: Boolean,
        val isLastPage: Boolean,
        val loadMoreFailed: Boolean = false,
        /** Sin conexión: la lista es la guardada en el teléfono y puede estar desactualizada. */
        val isFromCache: Boolean = false,
    ) : DealsUiState
    data class Error(val message: String) : DealsUiState
}

/** Páginas acumuladas de [query]; [query] null = todavía no llegó ninguna. */
private data class Paging(
    val query: DealsQuery? = null,
    val items: List<Deal> = emptyList(),
    val cursor: DocumentSnapshot? = null,
    val isLastPage: Boolean = false,
    val isFromCache: Boolean = false,
    val totalCount: Int? = null,
    val filteredCount: Int? = null,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val error: String? = null,
)

/** Atajos de la fila de filtros, combinables entre sí y con tiendas, géneros y búsqueda. */
data class QuickFilters(val freeOnly: Boolean = false, val endingSoon: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DealsViewModel(
    private val repository: DealsRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    /**
     * Texto del buscador como estado de Compose (no StateFlow): el TextField necesita actualizarse de
     * forma síncrona en cada tecla, o se pierden caracteres y salta el cursor. Para filtrar se
     * convierte en Flow con snapshotFlow.
     */
    var searchQuery by mutableStateOf("")
        private set

    /** Géneros elegidos (selección múltiple); vacío = sin filtro de género. */
    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    /**
     * Tiendas elegidas (selección múltiple); vacío = "Todas", así desmarcar la última vuelve solo a ver todo.
     * Arranca donde el usuario la dejó (o con las tiendas que eligió en la bienvenida).
     */
    private val _selectedPlatforms = MutableStateFlow(preferences.state.value.selectedStores)
    val selectedPlatforms: StateFlow<Set<StorePlatform>> = _selectedPlatforms.asStateFlow()

    private val _quickFilters = MutableStateFlow(QuickFilters())
    val quickFilters: StateFlow<QuickFilters> = _quickFilters.asStateFlow()

    /** Tiendas y umbral de alertas de este teléfono; las tiendas de alertas van primero en los chips. */
    val storePreferences: StateFlow<StorePreferences> = preferences.state

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Cada incremento vuelve a pedir la primera página de los filtros actuales. */
    private val reloadTrigger = MutableStateFlow(0)

    private val paging = MutableStateFlow(Paging())
    private var loadMoreJob: Job? = null

    /** País de las ofertas: cambiarlo en Ajustes reinicia la lista, los géneros y la vigencia. */
    private val country: StateFlow<Country> = preferences.state
        .map { it.country }
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.state.value.country)

    private val lastRuns: StateFlow<Map<String, Long>> = country
        .flatMapLatest { repository.observeLastRuns(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Géneros de las tiendas elegidas (de todas si no hay selección), según config/genres. */
    val availableGenres: StateFlow<List<String>> = combine(country.flatMapLatest { repository.observeGenres(it) }, _selectedPlatforms) { byPlatform, platforms ->
        val lists = if (platforms.isEmpty()) byPlatform.values else platforms.mapNotNull { byPlatform[it.id] }
        lists.flatten().distinct().sortedWith(Collator.getInstance()) // orden alfabético con tildes: "Único" junto a la U
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Se busca al dejar de escribir, no en cada tecla: cada búsqueda es una consulta a Firestore.
        // Vaciar el buscador sí responde al instante.
        val search = snapshotFlow { searchQuery.trim() }
            .distinctUntilChanged()
            .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        // combine admite 5 flujos con tipo: país y atajos viajan juntos.
        val countryAndQuick = combine(country, _quickFilters) { c, q -> c to q }
        val query = combine(_selectedPlatforms, _selectedGenres, search, availableGenres, countryAndQuick) { platforms, selected, text, genres, (country, quick) ->
            // Solo cuentan los elegidos que existen en las tiendas actuales: uno que desapareció tras una
            // actualización (o al cambiar de tienda) se ignora en vez de dejar la lista vacía.
            DealsQuery(
                country = country,
                platforms = platforms.mapTo(HashSet()) { it.id },
                genres = selected.intersect(genres.toSet()),
                search = text,
                freeOnly = quick.freeOnly,
                endingSoon = quick.endingSoon,
            )
        }.distinctUntilChanged()
        viewModelScope.launch {
            // collectLatest: si los filtros cambian mientras llega una página, se cancela y se pide la de los nuevos.
            combine(query, reloadTrigger) { q, _ -> q }.collectLatest(::loadFirstPage)
        }
    }

    val uiState: StateFlow<DealsUiState> = combine(paging, lastRuns, repository.minuteTicker()) { p, runs, now ->
        val query = p.query
        when {
            p.error != null -> DealsUiState.Error(p.error)
            query == null -> DealsUiState.Loading
            else -> DealsUiState.Success(
                deals = p.items.filter { DealsRepository.isActive(it, now, runs[it.platform]) },
                totalCount = p.totalCount,
                filteredCount = p.filteredCount,
                isFiltered = query.isFiltered,
                query = query,
                isInitialLoading = p.isInitialLoading,
                isLoadingMore = p.isLoadingMore,
                isLastPage = p.isLastPage,
                loadMoreFailed = p.loadMoreFailed,
                isFromCache = p.isFromCache,
            )
        }
    }
        .flowOn(Dispatchers.Default) // el filtrado no corre en el hilo principal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsUiState.Loading)

    /** Reinicia la paginación: la lista anterior sigue visible (con [Paging.isInitialLoading]) hasta que llega esta. */
    private suspend fun loadFirstPage(query: DealsQuery) {
        loadMoreJob?.cancel()
        paging.update { it.copy(isInitialLoading = true, isLoadingMore = false, loadMoreFailed = false, error = null) }
        val (deals, page) = try {
            fetchVisible(query, after = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            paging.update { it.copy(isInitialLoading = false, error = e.message ?: "Error desconocido") }
            return
        }
        val previous = paging.value
        paging.value = Paging(
            query = query,
            // Mismas tiendas: el total no cambia, así el contador no parpadea mientras se recalcula.
            totalCount = previous.totalCount.takeIf {
                previous.query?.platforms == query.platforms && previous.query.country == query.country
            },
            items = deals,
            cursor = page.cursor,
            isLastPage = page.isLastPage,
            isFromCache = page.isFromCache,
            isInitialLoading = false,
        )
        // Después de mostrar la página: el contador no debe demorar la lista.
        val (total, filtered) = coroutineScope {
            val total = async { repository.countDeals(DealsQuery(country = query.country, platforms = query.platforms)) }
            val filtered = async { if (query.isFiltered) repository.countDeals(query) else null }
            total.await() to filtered.await()
        }
        paging.update { if (it.query == query) it.copy(totalCount = total, filteredCount = filtered) else it }
    }

    /**
     * Pide la página siguiente; la llama la grilla al acercarse al final. No hace nada si ya hay una en camino,
     * si se cargó todo o si la anterior falló (salvo [retry]: el botón "Reintentar").
     */
    fun loadNextPage(retry: Boolean = false) {
        val current = paging.value
        val query = current.query ?: return
        if (current.isLastPage || current.isInitialLoading || current.isLoadingMore) return
        if (current.loadMoreFailed && !retry) return
        loadMoreJob = viewModelScope.launch {
            paging.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
            try {
                val (deals, page) = fetchVisible(query, current.cursor)
                paging.update {
                    if (it.query != query) return@update it
                    it.copy(
                        // Un precio que cambió entre páginas puede mover una oferta a la siguiente: sin duplicados.
                        items = (it.items + deals).distinctBy { deal -> deal.id },
                        cursor = page.cursor,
                        isLastPage = page.isLastPage,
                        isFromCache = page.isFromCache,
                        isLoadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                paging.update { it.copy(isLoadingMore = false) }
                throw e
            } catch (e: Exception) {
                paging.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    /**
     * Páginas desde [after] hasta juntar [MIN_VISIBLE_PER_LOAD] ofertas vigentes (o llegar al final). Una página
     * puede quedar casi vacía tras los filtros del teléfono (vigencia, géneros o palabras que no van al
     * servidor), y entonces la grilla no tendría con qué desplazarse para pedir la siguiente.
     */
    private suspend fun fetchVisible(query: DealsQuery, after: DocumentSnapshot?): Pair<List<Deal>, DealsPage> {
        val deals = mutableListOf<Deal>()
        var cursor = after
        var page: DealsPage
        var requests = 0
        do {
            page = repository.getDealsPage(query, cursor)
            deals += page.deals
            cursor = page.cursor
            requests++
            val now = System.currentTimeMillis()
            val visible = deals.count { DealsRepository.isActive(it, now, lastRuns.value[it.platform]) }
        } while (!page.isLastPage && visible < MIN_VISIBLE_PER_LOAD && requests < MAX_REQUESTS_PER_LOAD)
        return deals to page
    }

    fun onSearchQueryChange(query: String) {
        searchQuery = query
    }

    /** Alterna el género: si estaba elegido lo quita, si no lo agrega (los demás se mantienen). */
    fun onGenreClick(genre: String) = _selectedGenres.update { if (genre in it) it - genre else it + genre }

    /** Alterna la tienda (las demás se mantienen); desmarcar la última equivale a "Todas". Se recuerda al reabrir. */
    fun onPlatformClick(platform: StorePlatform) {
        _selectedPlatforms.update { if (platform in it) it - platform else it + platform }
        preferences.setSelectedStores(_selectedPlatforms.value)
    }

    /** Chip "Todas": limpia la selección de tiendas. */
    fun onAllPlatformsClick() {
        _selectedPlatforms.value = emptySet()
        preferences.setSelectedStores(emptySet())
    }

    fun onFreeOnlyClick() = _quickFilters.update { it.copy(freeOnly = !it.freeOnly) }

    fun onEndingSoonClick() = _quickFilters.update { it.copy(endingSoon = !it.endingSoon) }

    /** Desde Ajustes: el país reinicia la lista (otra colección) y, con las alertas, los temas de FCM. */
    fun saveSettings(country: Country, stores: Set<StorePlatform>, minDiscount: Int) {
        preferences.setCountry(country)
        preferences.setAlerts(stores, minDiscount)
    }

    /** No cambia la tienda: limpia búsqueda, géneros y atajos dentro de la tienda actual. */
    fun clearFilters() {
        searchQuery = ""
        _selectedGenres.value = emptySet()
        _quickFilters.value = QuickFilters()
    }

    /**
     * Vuelve a pedir la primera página (precios del día, o reintento tras un error) y mantiene el indicador
     * visible un instante para que el gesto tenga respuesta visual.
     */
    fun refreshDeals() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            reloadTrigger.update { it + 1 }
            delay(1_000)
            _isRefreshing.value = false
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val MIN_VISIBLE_PER_LOAD = 12
        /** Tope de páginas por carga: con una búsqueda muy filtrada en el teléfono no se recorre toda la colección. */
        private const val MAX_REQUESTS_PER_LOAD = 4

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DealsViewModel(DealsRepository(), (this[APPLICATION_KEY] as PSDealsApp).preferences) }
        }
    }
}
