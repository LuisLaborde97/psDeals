package com.example.psdeals.data

import android.util.Log
import com.example.psdeals.model.UserProfile
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Copia inmutable de lo que la UI necesita de FirebaseUser (que es mutable y no sirve para comparar estados). */
data class SessionUser(
    val uid: String,
    val isAnonymous: Boolean,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
)

/** Coordina la sesión de Firebase Auth con el perfil `users/{uid}`, para que nunca queden desalineados. */
class UserSession(
    private val auth: AuthRepository = AuthRepository(),
    private val profiles: UserProfileRepository = UserProfileRepository(),
) {
    /** El perfil del uid actual ya existe en Firestore (start() terminó al menos una vez). */
    @Volatile
    private var profileReady = false

    /**
     * Fuerza a releer el usuario: reload() y updateProfile() cambian FirebaseUser sin renovar el token,
     * así que el IdTokenListener no se entera y la UI seguiría mostrando la foto anterior.
     */
    private val profileRefreshes = MutableStateFlow(0)

    val sessionUser: Flow<SessionUser?> = combine(auth.authState, profileRefreshes) { user, _ -> user?.toSessionUser() }
        .distinctUntilChanged()

    /** Perfil del usuario actual en tiempo real; cambia de documento si cambia el uid. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val profile: Flow<UserProfile?> = sessionUser
        .map { it?.uid }
        .distinctUntilChanged()
        .flatMapLatest { uid ->
            // Tras cerrar sesión, el listener del uid anterior puede recibir PERMISSION_DENIED antes de cancelarse.
            if (uid == null) flowOf(null) else profiles.observeProfile(uid).catch { emit(null) }
        }

    /** Al abrir la app: sesión anónima silenciosa si no había ninguna, y su perfil creado. */
    suspend fun start(): FirebaseUser {
        val user = auth.ensureSignedIn()
        profiles.ensureProfile(user)
        profileReady = true
        // Al abrir la app también: el usuario pudo cambiar su foto de Google desde la última vez.
        if (!user.isAnonymous) refreshGoogleProfile()
        return user
    }

    /**
     * Entra con Google conservando los datos del usuario anónimo. Lo normal es que la vinculación
     * mantenga el uid; si la cuenta de Google ya existía, se fusionan la wishlist y el historial
     * anónimos en ella para que el usuario no pierda nada.
     */
    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        // Se lee antes de entrar: si la sesión cambia de uid, las reglas ya no dejarán leer este perfil.
        val anonymousProfile = auth.currentUser?.takeIf { it.isAnonymous }?.let { profiles.getProfile(it.uid) }

        val result = auth.signInWithGoogle(idToken)
        profiles.ensureProfile(result.user) // Linked: isAnonymous pasa a false. Switched: perfil existente o nuevo.
        profileReady = true
        if (result is GoogleSignInResult.SwitchedToExistingAccount && anonymousProfile != null) {
            profiles.mergeInto(anonymousProfile, result.user.uid)
        }
        refreshGoogleProfile()
        return result.user
    }

    /** Foto y nombre más recientes de Google; si falla (sin red), se queda con lo que había. */
    private suspend fun refreshGoogleProfile() {
        runCatching { auth.refreshProfileFromGoogle() }
            .onFailure { Log.w(TAG, "No se pudo refrescar el perfil de Google", it) }
        profileRefreshes.update { it + 1 }
    }

    suspend fun setWishlisted(dealId: String, wishlisted: Boolean) {
        // Si start() falló al abrir la app (sin conexión), el documento aún no existe y update() fallaría.
        val uid = if (profileReady) auth.currentUser?.uid ?: start().uid else start().uid
        if (wishlisted) profiles.addToWishlist(uid, dealId) else profiles.removeFromWishlist(uid, dealId)
    }

    /** Guarda el término en searchHistory (sin duplicados, últimas UserProfileRepository.MAX_SEARCH_HISTORY). */
    suspend fun recordSearch(query: String) {
        val uid = if (profileReady) auth.currentUser?.uid ?: start().uid else start().uid
        profiles.recordSearch(uid, query)
    }

    /** Vuelve a un usuario anónimo nuevo (con perfil vacío). */
    suspend fun signOut(): FirebaseUser {
        profileReady = false
        auth.signOut()
        return start()
    }

    private fun FirebaseUser.toSessionUser(): SessionUser {
        // providerData de Google se actualiza en cada inicio de sesión; los campos de FirebaseUser no.
        val google = providerData.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        return SessionUser(
            uid = uid,
            isAnonymous = isAnonymous,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: google?.displayName,
            email = email ?: google?.email,
            photoUrl = (google?.photoUrl ?: photoUrl)?.toString(),
        )
    }

    private companion object {
        const val TAG = "UserSession"
    }
}
