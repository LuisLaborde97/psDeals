package co.golab.deals.data

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Resultado de entrar con Google. */
sealed interface GoogleSignInResult {
    val user: FirebaseUser

    /** El usuario anónimo se convirtió en cuenta de Google: mismo uid, sus datos siguen ahí. */
    data class Linked(override val user: FirebaseUser) : GoogleSignInResult

    /**
     * Esa cuenta de Google ya pertenecía a otro usuario de Firebase (p. ej. se registró antes en otro
     * teléfono o reinstaló la app), así que no se pudo vincular y la sesión pasó a esa cuenta.
     * El usuario anónimo [abandonedAnonymousUid] queda huérfano: sus datos hay que fusionarlos aparte.
     */
    data class SwitchedToExistingAccount(
        override val user: FirebaseUser,
        val abandonedAnonymousUid: String,
    ) : GoogleSignInResult

    /** Ya tenía una cuenta no anónima y volvió a entrar con Google. */
    data class SignedIn(override val user: FirebaseUser) : GoogleSignInResult
}

class AuthRepository(
    private val auth: FirebaseAuth = Firebase.auth,
) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    /**
     * Emite el usuario cada vez que cambia la sesión. IdTokenListener y no AuthStateListener: este
     * último no avisa al vincular Google (el uid no cambia), y la UI necesita enterarse.
     * Ojo: FirebaseUser es mutable; quien lo exponga a la UI debe copiar sus datos.
     */
    val authState: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.IdTokenListener { trySend(it.currentUser) }
        auth.addIdTokenListener(listener)
        awaitClose { auth.removeIdTokenListener(listener) }
    }

    /**
     * Sesión garantizada: reutiliza la persistida o crea una anónima en silencio. Firebase guarda la
     * sesión en disco, así que el usuario anónimo (y su uid) sobrevive a cerrar la app.
     */
    suspend fun ensureSignedIn(): FirebaseUser =
        auth.currentUser
            ?: auth.signInAnonymously().await().user
            ?: error("signInAnonymously terminó sin usuario")

    /**
     * Entra con Google conservando los datos del usuario anónimo: se hace linkWithCredential sobre él,
     * no signInWithCredential, que crearía otro uid y dejaría atrás su wishlist e historial.
     *
     * @param idToken token de Google (ver GoogleIdTokenProvider).
     */
    suspend fun signInWithGoogle(idToken: String): GoogleSignInResult {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = ensureSignedIn()
        if (!user.isAnonymous) {
            return GoogleSignInResult.SignedIn(auth.signInWithCredential(credential).await().user ?: user)
        }
        return try {
            GoogleSignInResult.Linked(user.linkWithCredential(credential).await().user ?: user)
        } catch (e: FirebaseAuthUserCollisionException) {
            // El token ya se consumió en el intento de vincular: Firebase entrega uno nuevo en la excepción.
            val existing = auth.signInWithCredential(e.updatedCredential ?: credential).await().user
                ?: error("signInWithCredential terminó sin usuario")
            GoogleSignInResult.SwitchedToExistingAccount(existing, abandonedAnonymousUid = user.uid)
        }
    }

    /**
     * Trae del servidor los datos más recientes del usuario y sincroniza nombre y foto con los de Google.
     * Firebase solo copia displayName/photoUrl del proveedor la primera vez que se vincula: si la foto se
     * añadió o cambió después, el usuario la conserva vacía o vieja, aunque providerData sí tenga la nueva.
     */
    suspend fun refreshProfileFromGoogle(): FirebaseUser? {
        val user = auth.currentUser ?: return null
        user.reload().await()
        val google = user.providerData.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID } ?: return user
        if (google.photoUrl != user.photoUrl || (user.displayName.isNullOrBlank() && !google.displayName.isNullOrBlank())) {
            val update = UserProfileChangeRequest.Builder()
                .setPhotoUri(google.photoUrl)
                .apply { if (user.displayName.isNullOrBlank()) setDisplayName(google.displayName) }
                .build()
            user.updateProfile(update).await()
        }
        return user
    }

    /**
     * Confirma la identidad con un token de Google recién pedido. Firebase exige un inicio de sesión reciente
     * para borrar la cuenta (si no, lanza FirebaseAuthRecentLoginRequiredException); pedirlo siempre, en vez
     * de esperar ese error, hace que el usuario confirme con su cuenta antes de una acción irreversible.
     * @throws com.google.firebase.auth.FirebaseAuthInvalidUserException si eligió otra cuenta de Google.
     */
    suspend fun reauthenticateWithGoogle(idToken: String): FirebaseUser {
        val user = auth.currentUser ?: error("No hay sesión que confirmar")
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
        return user
    }

    /** Borra el usuario de Firebase Authentication (con su nombre, correo y foto de Google) y cierra la sesión. */
    suspend fun deleteCurrentUser() {
        auth.currentUser?.delete()?.await()
    }

    /** Cierra la sesión; el siguiente ensureSignedIn() creará un usuario anónimo nuevo. */
    fun signOut() = auth.signOut()
}
