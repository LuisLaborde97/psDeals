package co.golab.deals.data

import android.util.Log
import co.golab.deals.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.messaging
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

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
    private val pushTokens: PushTokenStore,
    private val auth: AuthRepository = AuthRepository(),
    private val profiles: UserProfileRepository = UserProfileRepository(),
    private val messaging: FirebaseMessaging = Firebase.messaging,
) {
    /** El perfil del uid actual ya existe en Firestore (start() terminó al menos una vez). */
    @Volatile
    private var profileReady = false

    /** El arranque, la vinculación y la rotación del token (desde el servicio FCM) pueden coincidir. */
    private val tokenMutex = Mutex()

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
        registerPushTokenSafely()
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
        // Se quita el token del perfil anónimo mientras aún se puede escribir en él: si la cuenta de Google ya
        // existía, el perfil anónimo queda huérfano con la misma wishlist y el teléfono recibiría cada alerta dos veces.
        if (anonymousProfile != null) unregisterPushToken()

        try {
            val result = auth.signInWithGoogle(idToken)
            profiles.ensureProfile(result.user) // Linked: isAnonymous pasa a false. Switched: perfil existente o nuevo.
            profileReady = true
            if (result is GoogleSignInResult.SwitchedToExistingAccount && anonymousProfile != null) {
                profiles.mergeInto(anonymousProfile, result.user.uid)
            }
            refreshGoogleProfile()
            return result.user
        } finally {
            // Éxito: en la cuenta de Google. Fallo: de vuelta en el anónimo. Cada caso con su sesión real.
            registerPushTokenSafely()
        }
    }

    /**
     * Registra el token de FCM de este dispositivo en el perfil actual (sin duplicados) y, si FCM lo
     * rotó, quita el anterior. Lo llaman start(), la vinculación y el servicio FCM ([newToken]).
     */
    suspend fun registerPushToken(newToken: String? = null): Unit = tokenMutex.withLock {
        val user = auth.currentUser ?: return@withLock
        // Sin perfil aún (start() en curso o falló): start() lo registrará al terminar.
        if (!profileReady) return@withLock
        // getToken() está marcado obsoleto en favor de register(), pero no es un reemplazo directo: register()
        // entrega un Firebase Installation ID (otro modelo de envío, opt-in por manifiesto) y, una vez activado,
        // getToken() lanza excepción. El backend (firebase-admin) envía por token, así que seguimos en ese modelo.
        @Suppress("DEPRECATION")
        val token = newToken ?: messaging.token.await()
        val previous = pushTokens.lastToken
        if (previous != null && previous != token && pushTokens.lastUid == user.uid) {
            runCatching { profiles.removeFcmToken(user.uid, previous) }
                .onFailure { Log.w(TAG, "No se pudo quitar el token anterior", it) }
        }
        profiles.addFcmToken(user.uid, token)
        pushTokens.save(user.uid, token)
        Log.d(TAG, "Token de FCM registrado en el perfil")
    }

    /** Sin red no debe tumbar el arranque ni la vinculación: se reintenta en el próximo arranque. */
    private suspend fun registerPushTokenSafely() {
        runCatching { registerPushToken() }.onFailure { Log.w(TAG, "No se pudo registrar el token de FCM", it) }
    }

    /** Quita el token de este dispositivo del perfil actual (al cerrar sesión o antes de cambiar de cuenta). */
    private suspend fun unregisterPushToken(): Unit = tokenMutex.withLock {
        val uid = auth.currentUser?.uid ?: return@withLock
        val token = pushTokens.lastToken?.takeIf { pushTokens.lastUid == uid } ?: return@withLock
        runCatching { profiles.removeFcmToken(uid, token) }
            .onFailure { Log.w(TAG, "No se pudo quitar el token de FCM", it) }
        pushTokens.clear()
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

    suspend fun removeSearch(query: String) {
        val uid = if (profileReady) auth.currentUser?.uid ?: start().uid else start().uid
        profiles.removeSearch(uid, query)
    }

    /** Vuelve a un usuario anónimo nuevo (con perfil vacío). */
    suspend fun signOut(): FirebaseUser {
        // Antes de salir (después ya no se puede escribir en ese perfil): este teléfono deja de recibir
        // las alertas de esa cuenta. start() registra el token en el anónimo nuevo.
        unregisterPushToken()
        profileReady = false
        auth.signOut()
        return start()
    }

    /**
     * Elimina la cuenta (requisito de Google Play): confirma la identidad con [idToken], borra el perfil
     * `users/{uid}` y después el usuario de Firebase Auth. En ese orden porque las reglas solo dejan borrar
     * el perfil a su dueño con sesión iniciada. Termina en un usuario anónimo nuevo, como al cerrar sesión.
     */
    suspend fun deleteAccount(idToken: String): FirebaseUser {
        val user = auth.reauthenticateWithGoogle(idToken)
        // El token de FCM se va con el perfil: solo se olvida la copia local para no intentar quitarlo después.
        tokenMutex.withLock { pushTokens.clear() }
        profileReady = false
        profiles.deleteProfile(user.uid)
        auth.deleteCurrentUser()
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
