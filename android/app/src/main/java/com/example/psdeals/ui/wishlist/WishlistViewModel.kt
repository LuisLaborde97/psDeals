package com.example.psdeals.ui.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.psdeals.PSDealsApp
import com.example.psdeals.data.DealsRepository
import com.example.psdeals.data.UserSession
import com.example.psdeals.model.Deal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface WishlistUiState {
    data object Loading : WishlistUiState

    /** @param deals la más reciente primero. */
    data class Success(val deals: List<Deal>) : WishlistUiState
    data class Error(val message: String) : WishlistUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class WishlistViewModel(
    session: UserSession,
    private val repository: DealsRepository,
) : ViewModel() {

    val uiState: StateFlow<WishlistUiState> = session.profile
        .filterNotNull() // null = perfil cargando o sesión cambiando: se queda en Loading
        .map { it.wishlist }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            // arrayUnion agrega al final: se invierte para mostrar primero lo último que guardó.
            val order = ids.asReversed().withIndex().associate { (index, id) -> id to index }
            repository.getDealsByIds(ids)
                .map<List<Deal>, WishlistUiState> { deals -> WishlistUiState.Success(deals.sortedBy { order[it.id] }) }
                .catch { emit(WishlistUiState.Error(it.message ?: "Error desconocido")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WishlistUiState.Loading)

    fun isExpired(deal: Deal): Boolean = !DealsRepository.isActive(deal)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { WishlistViewModel((this[APPLICATION_KEY] as PSDealsApp).userSession, DealsRepository()) }
        }
    }
}
