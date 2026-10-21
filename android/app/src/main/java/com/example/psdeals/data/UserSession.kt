package com.example.psdeals.data

import com.example.psdeals.model.UserProfile
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    val sessionUser: Flow<SessionUser?> = auth.authState
        .map { it?.toSessionUser() }
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
        return result.user
    }

    suspend fun setWishlisted(dealId: String, wishlisted: Boolean) {
        // Si start() falló al abrir la app (sin conexión), el documento aún no existe y update() fallaría.
        val uid = if (profileReady) auth.currentUser?.uid ?: start().uid else start().uid
        if (wishlisted) profiles.addToWishlist(uid, dealId) else profiles.removeFromWishlist(uid, dealId)
    }

    /** Vuelve a un usuario anónimo nuevo (con perfil vacío). */
    suspend fun signOut(): FirebaseUser {
        profileReady = false
        auth.signOut()
        return start()
    }

    private fun FirebaseUser.toSessionUser() = SessionUser(
        uid = uid,
        isAnonymous = isAnonymous,
        displayName = displayName,
        email = email,
        photoUrl = photoUrl?.toString(),
    )
}
