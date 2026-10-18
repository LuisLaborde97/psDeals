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
     * @param deals lista ya filtrada por búsqueda y género.
     * @param totalCount ofertas antes de filtrar.
     * @param isFiltered hay búsqueda o género activos (distingue "sin ofertas" de "sin coincidencias").
     */
    data class Success(val deals: List<Deal>, val totalCount: Int, val isFiltered: Boolean) : DealsUiState
    data class Error(val message: String) : DealsUiState
}

/** Estado crudo de Firestore, antes de aplicar filtros. */
private sealed interface DealsSource {
    data object Loading : DealsSource
    data class Loaded(val deals: List<Deal>) : DealsSource
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

    /** Géneros presentes en las ofertas actuales (no en la lista filtrada, para que los chips no desaparezcan). */
    val availableGenres: StateFlow<List<String>> = source
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
        source,
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
