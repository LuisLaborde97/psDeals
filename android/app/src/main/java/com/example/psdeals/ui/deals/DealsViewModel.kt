package com.example.psdeals.ui.deals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.psdeals.data.DealsRepository
import com.example.psdeals.model.Deal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface DealsUiState {
    data object Loading : DealsUiState
    data class Success(val deals: List<Deal>) : DealsUiState
    data class Error(val message: String) : DealsUiState
}

class DealsViewModel(private val repository: DealsRepository) : ViewModel() {

    /** Cada incremento vuelve a suscribirse al Flow de Firestore (botón "Reintentar"). */
    private val retryTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DealsUiState> = retryTrigger
        .flatMapLatest {
            repository.getDeals()
                .map<List<Deal>, DealsUiState> { DealsUiState.Success(it) }
                .onStart { emit(DealsUiState.Loading) }
                .catch { emit(DealsUiState.Error(it.message ?: "Error desconocido")) }
        }
        // 5 s de gracia: el listener sobrevive a una rotación pero se cierra si la app pasa a segundo plano.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DealsUiState.Loading)

    fun retry() = retryTrigger.update { it + 1 }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DealsViewModel(DealsRepository()) }
        }
    }
}
