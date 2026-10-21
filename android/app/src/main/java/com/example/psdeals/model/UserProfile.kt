package com.example.psdeals.model

/**
 * Perfil del usuario en `users/{uid}`. Se mapea a mano (ver UserProfileRepository): con toObject(),
 * Firestore guardaría `isAnonymous` como "anonymous".
 */
data class UserProfile(
    val uid: String,
    /** true hasta que vincula una cuenta de Google; el uid no cambia al vincular. */
    val isAnonymous: Boolean,
    /** IDs de documentos de `deals` (ej. "steam-1091500"). */
    val wishlist: List<String> = emptyList(),
    /** Búsquedas recientes, la más reciente primero; acotada a UserProfileRepository.MAX_SEARCH_HISTORY. */
    val searchHistory: List<String> = emptyList(),
    /** Epoch millis; null mientras el timestamp del servidor no se ha confirmado. */
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)
