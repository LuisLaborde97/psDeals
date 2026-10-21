package com.example.psdeals.data

import com.google.firebase.auth.FirebaseUser

/** Coordina la sesión de Firebase Auth con el perfil `users/{uid}`, para que nunca queden desalineados. */
class UserSession(
    private val auth: AuthRepository = AuthRepository(),
    private val profiles: UserProfileRepository = UserProfileRepository(),
) {
    /** Al abrir la app: sesión anónima silenciosa si no había ninguna, y su perfil creado. */
    suspend fun start(): FirebaseUser {
        val user = auth.ensureSignedIn()
        profiles.ensureProfile(user)
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
        if (result is GoogleSignInResult.SwitchedToExistingAccount && anonymousProfile != null) {
            profiles.mergeInto(anonymousProfile, result.user.uid)
        }
        return result.user
    }

    /** Vuelve a un usuario anónimo nuevo (con perfil vacío). */
    suspend fun signOut(): FirebaseUser {
        auth.signOut()
        return start()
    }
}
