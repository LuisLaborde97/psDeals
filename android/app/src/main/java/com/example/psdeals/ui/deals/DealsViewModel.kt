package com.example.psdeals.ui.deals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.psdeals.data.DealsFeed
import com.example.psdeals.data.DealsRepository
import com.example.psdeals.model.Deal
import com.example.psdeals.model.StorePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.Normalizer

sealed interface DealsUiState {
    data object Loading : DealsUiState

    /**
     * @param deals lista ya filtrada por tienda, búsqueda y género.
     * @param totalCount ofertas de las tiendas elegidas (o de todas) antes de filtrar por búsqueda y género.
     * @param isFiltered hay búsqueda o género activos (distingue "sin ofertas" de "sin coincidencias").
     * @param platforms tiendas de las que salen [deals] (vacío = todas). Viaja con los datos y no aparte
     *   para que la UI sepa exactamente cuándo llegó la lista de la selección nueva.
     * @param isSearching hay texto en el buscador; igual que [platforms], marca cuándo llega la lista
     *   sin filtrar al vaciarlo.
     */
    data class Success(
        val deals: List<Deal>,
        val totalCount: Int,
        val isFiltered: Boolean,
        val platforms: Set<StorePlatform>,
        val isSearching: Boolean,
        /** Sin conexión: la lista es la guardada en el teléfono y puede estar desactualizada. */
        val isFromCache: Boolean = false,
    ) : DealsUiState
    data class Error(val message: String) : DealsUiState
}

/** Estado crudo de Firestore, antes de aplicar filtros. */
private sealed interface DealsSource {
    data object Loading : DealsSource
    /** @param platforms tiendas por las que ya se filtró [deals] (vacío = sin filtrar). */
    data class Loaded(
        val deals: List<Deal>,
        val platforms: Set<StorePlatform> = emptySet(),
        val isFromCache: Boolean = false,
    ) : DealsSource
    data class Failed(val message: String) : DealsSource
}

@OptIn(ExperimentalCoroutinesApi::class)
class DealsViewModel(private val repository: DealsRepository) : ViewModel() {

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
     * Arranca en PlayStation, como antes de la selección múltiple.
     */
    private val _selectedPlatforms = MutableStateFlow(setOf(StorePlatform.PLAYSTATION))
    val selectedPlatforms: StateFlow<Set<StorePlatform>> = _selectedPlatforms.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Cada incremento vuelve a crear el listener de Firestore. */
    private val reloadTrigger = MutableStateFlow(0)

    // Sin onStart(Loading): al refrescar se conserva la lista actual en vez de parpadear al spinner.
    private val source: StateFlow<DealsSource> = reloadTrigger
        .flatMapLatest {
            repository.getDeals()
                .map<DealsFeed, DealsSource> { DealsSource.Loaded(it.deals, isFromCache = it.isFromCache) }
                .catch { emit(DealsSource.Failed(it.message ?: "Error desconocido")) }
        }
        // 5 s de gracia: el listener sobrevive a una rotación pero se cierra si la app pasa a segundo plano.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsSource.Loading)

    /**
     * Primer filtro (OR entre tiendas): ofertas de cualquiera de las tiendas elegidas, o de todas. Los géneros
     * disponibles y los demás filtros parten de aquí, así que se combinan con AND.
     */
    private val platformSource: StateFlow<DealsSource> = combine(source, _selectedPlatforms) { state, platforms ->
        when {
            state !is DealsSource.Loaded -> state
            platforms.isEmpty() -> state.copy(platforms = emptySet())
            else -> {
                val ids = platforms.mapTo(HashSet()) { it.id }
                state.copy(deals = state.deals.filter { it.platform in ids }, platforms = platforms)
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsSource.Loading)

    /**
     * Géneros presentes en las ofertas de la tienda elegida (no en la lista filtrada por búsqueda o
     * género, para que los chips no desaparezcan).
     */
    val availableGenres: StateFlow<List<String>> = platformSource
        .map { state ->
            (state as? DealsSource.Loaded)?.deals.orEmpty()
                .flatMap { it.genres }
                .distinct()
                .sortedWith(Collator.getInstance()) // orden alfabético con tildes: "Único" junto a la U
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<DealsUiState> = combine(
        platformSource,
        snapshotFlow { searchQuery },
        _selectedGenres,
        availableGenres,
    ) { source, query, selected, genres ->
        when (source) {
            DealsSource.Loading -> DealsUiState.Loading
            is DealsSource.Failed -> DealsUiState.Error(source.message)
            is DealsSource.Loaded -> {
                // Solo cuentan los elegidos que existen en la tienda actual: uno que desapareció tras una
                // actualización (o al cambiar de tienda) se ignora en vez de dejar la lista vacía.
                val activeGenres = selected.intersect(genres.toSet())
                val needle = query.trim().normalizedForSearch()
                val filtered = source.deals.filter { deal ->
                    // OR: basta con que el juego tenga uno de los géneros elegidos.
                    (activeGenres.isEmpty() || deal.genres.any { it in activeGenres }) &&
                        (needle.isEmpty() || deal.title.normalizedForSearch().contains(needle))
                }
                DealsUiState.Success(
                    deals = filtered,
                    totalCount = source.deals.size,
                    isFiltered = activeGenres.isNotEmpty() || needle.isNotEmpty(),
                    platforms = source.platforms,
                    isSearching = needle.isNotEmpty(),
                    isFromCache = source.isFromCache,
                )
            }
        }
    }
        .flowOn(Dispatchers.Default) // el filtrado no corre en el hilo principal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsUiState.Loading)

    fun onSearchQueryChange(query: String) {
        searchQuery = query
    }

    /** Alterna el género: si estaba elegido lo quita, si no lo agrega (los demás se mantienen). */
    fun onGenreClick(genre: String) = _selectedGenres.update { if (genre in it) it - genre else it + genre }

    /** Alterna la tienda (las demás se mantienen); desmarcar la última equivale a "Todas". */
    fun onPlatformClick(platform: StorePlatform) =
        _selectedPlatforms.update { if (platform in it) it - platform else it + platform }

    /** Chip "Todas": limpia la selección de tiendas. */
    fun onAllPlatformsClick() {
        _selectedPlatforms.value = emptySet()
    }

    /** No cambia la tienda: limpia búsqueda y géneros dentro de la tienda actual. */
    fun clearFilters() {
        searchQuery = ""
        _selectedGenres.value = emptySet()
    }

    /**
     * Firestore ya es tiempo real; refrescar recrea el listener (útil tras perder conexión) y mantiene
     * el indicador visible un instante para que el gesto tenga respuesta visual.
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DealsViewModel(DealsRepository()) }
        }
    }
}

/** Minúsculas y sin tildes: "pokemon" encuentra "Pokémon". */
private fun String.normalizedForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

private val DIACRITICS = "\\p{Mn}+".toRegex()
