package co.golab.deals.ui.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.golab.deals.PSDealsApp
import co.golab.deals.data.SessionUser
import co.golab.deals.data.UserSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountUiState(
    val user: SessionUser? = null,
    /** IDs de deals en la lista de deseos; vacía mientras el perfil carga. */
    val wishlist: Set<String> = emptySet(),
    /** Búsquedas recientes, la más reciente primero. */
    val searchHistory: List<String> = emptyList(),
    val isSigningIn: Boolean = false,
) {
    val isSignedInWithGoogle: Boolean get() = user != null && !user.isAnonymous
}

class AccountViewModel(private val session: UserSession) : ViewModel() {

    private val isSigningIn = MutableStateFlow(false)

    /** Mensajes de una sola vez para el Snackbar (Channel: no se repiten al rotar). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    // La wishlist sale del snapshot de Firestore, que ya refleja las escrituras locales al instante:
    // el corazón cambia sin esperar al servidor, sin estado optimista propio.
    val uiState: StateFlow<AccountUiState> = combine(session.sessionUser, session.profile, isSigningIn) { user, profile, signingIn ->
        AccountUiState(
            user = user,
            wishlist = profile?.wishlist.orEmpty().toSet(),
            searchHistory = profile?.searchHistory.orEmpty(),
            isSigningIn = signingIn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    /** El token lo obtiene la UI (Credential Manager necesita la Activity); aquí se vincula con Firebase. */
    fun signInWithGoogle(idToken: String) {
        if (isSigningIn.value) return
        viewModelScope.launch {
            isSigningIn.value = true
            runCatching { session.signInWithGoogle(idToken) }
                .onSuccess { _messages.send("Listo: tu lista de deseos queda guardada en tu cuenta de Google") }
                .onFailure {
                    Log.w(TAG, "Fallo al entrar con Google", it)
                    _messages.send("No se pudo vincular tu cuenta de Google")
                }
            isSigningIn.value = false
        }
    }

    fun toggleWishlist(dealId: String) {
        val wishlisted = dealId !in uiState.value.wishlist
        viewModelScope.launch {
            runCatching { session.setWishlisted(dealId, wishlisted) }.onFailure {
                Log.w(TAG, "Fallo al actualizar la wishlist", it)
                _messages.send("No se pudo actualizar tu lista de deseos")
            }
        }
    }

    /** Silencioso: si falla (sin conexión), perder una entrada del historial no merece interrumpir al usuario. */
    fun recordSearch(query: String) {
        viewModelScope.launch {
            runCatching { session.recordSearch(query) }.onFailure { Log.w(TAG, "Fallo al guardar la búsqueda", it) }
        }
    }

    fun removeSearch(query: String) {
        viewModelScope.launch {
            runCatching { session.removeSearch(query) }.onFailure {
                Log.w(TAG, "Fallo al borrar la búsqueda", it)
                _messages.send("No se pudo borrar la búsqueda")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { session.signOut() }
                .onSuccess { _messages.send("Sesión cerrada") }
                .onFailure { Log.w(TAG, "Fallo al cerrar sesión", it) }
        }
    }

    fun showMessage(message: String) {
        _messages.trySend(message)
    }

    companion object {
        private const val TAG = "AccountViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            // La sesión es del proceso (PSDealsApp), no de la pantalla: así start() y la UI comparten instancia.
            initializer { AccountViewModel((this[APPLICATION_KEY] as PSDealsApp).userSession) }
        }
    }
}
