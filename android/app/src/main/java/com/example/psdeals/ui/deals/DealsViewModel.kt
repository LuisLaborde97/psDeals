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
     * @param totalCount ofertas de la tienda elegida (o de todas) antes de filtrar por búsqueda y género.
     * @param isFiltered hay búsqueda o género activos (distingue "sin ofertas" de "sin coincidencias").
     * @param platform tienda de la que salen [deals] (null = todas). Viaja con los datos y no aparte
     *   para que la UI sepa exactamente cuándo llegó la lista de la tienda nueva.
     * @param isSearching hay texto en el buscador; igual que [platform], marca cuándo llega la lista
     *   sin filtrar al vaciarlo.
     */
    data class Success(
        val deals: List<Deal>,
        val totalCount: Int,
        val isFiltered: Boolean,
        val platform: StorePlatform?,
        val isSearching: Boolean,
    ) : DealsUiState
    data class Error(val message: String) : DealsUiState
}

/** Estado crudo de Firestore, antes de aplicar filtros. */
private sealed interface DealsSource {
    data object Loading : DealsSource
    /** @param platform tienda por la que ya se filtró [deals] (null = sin filtrar). */
    data class Loaded(val deals: List<Deal>, val platform: StorePlatform? = null) : DealsSource
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

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    /** Tienda elegida; null = "Todas". Funciona como pestaña: tocar la activa no la deselecciona. */
    private val _selectedPlatform = MutableStateFlow<StorePlatform?>(StorePlatform.PLAYSTATION)
    val selectedPlatform: StateFlow<StorePlatform?> = _selectedPlatform.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Cada incremento vuelve a crear el listener de Firestore. */
    private val reloadTrigger = MutableStateFlow(0)

    // Sin onStart(Loading): al refrescar se conserva la lista actual en vez de parpadear al spinner.
    private val source: StateFlow<DealsSource> = reloadTrigger
        .flatMapLatest {
            repository.getDeals()
                .map<List<Deal>, DealsSource> { DealsSource.Loaded(it) }
                .catch { emit(DealsSource.Failed(it.message ?: "Error desconocido")) }
        }
        // 5 s de gracia: el listener sobrevive a una rotación pero se cierra si la app pasa a segundo plano.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsSource.Loading)

    /** Ofertas de la tienda elegida (o todas); los géneros y el resto de filtros parten de aquí. */
    private val platformSource: StateFlow<DealsSource> = combine(source, _selectedPlatform) { state, platform ->
        when {
            state !is DealsSource.Loaded -> state
            platform == null -> state.copy(platform = null)
            else -> DealsSource.Loaded(state.deals.filter { it.platform == platform.id }, platform)
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
        _selectedGenre,
        availableGenres,
    ) { source, query, selected, genres ->
        when (source) {
            DealsSource.Loading -> DealsUiState.Loading
            is DealsSource.Failed -> DealsUiState.Error(source.message)
            is DealsSource.Loaded -> {
                // Si el género elegido ya no existe tras una actualización, se ignora en vez de dejar la lista vacía.
                val genre = selected?.takeIf { it in genres }
                val needle = query.trim().normalizedForSearch()
                val filtered = source.deals.filter { deal ->
                    (genre == null || genre in deal.genres) &&
                        (needle.isEmpty() || deal.title.normalizedForSearch().contains(needle))
                }
                DealsUiState.Success(
                    deals = filtered,
                    totalCount = source.deals.size,
                    isFiltered = genre != null || needle.isNotEmpty(),
                    platform = source.platform,
                    isSearching = needle.isNotEmpty(),
                )
            }
        }
    }
        .flowOn(Dispatchers.Default) // el filtrado no corre en el hilo principal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsUiState.Loading)

    fun onSearchQueryChange(query: String) {
        searchQuery = query
    }

    /** Tocar el chip seleccionado lo deselecciona. */
    fun onGenreClick(genre: String) = _selectedGenre.update { if (it == genre) null else genre }

    /** null = "Todas". */
    fun onPlatformClick(platform: StorePlatform?) {
        _selectedPlatform.value = platform
    }

    /** No cambia la tienda: limpia búsqueda y género dentro de la tienda actual. */
    fun clearFilters() {
        searchQuery = ""
        _selectedGenre.value = null
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
