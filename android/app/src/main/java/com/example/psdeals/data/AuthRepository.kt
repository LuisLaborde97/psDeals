package com.example.psdeals.data

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
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

    /** Cierra la sesión; el siguiente ensureSignedIn() creará un usuario anónimo nuevo. */
    fun signOut() = auth.signOut()
}
