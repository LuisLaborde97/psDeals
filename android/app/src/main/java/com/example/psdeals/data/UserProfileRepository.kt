package com.example.psdeals.data

import android.util.Log
import com.example.psdeals.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Perfiles en `users/{uid}`. Las reglas de Firestore deben limitar cada documento a su propio uid. */
class UserProfileRepository(
    private val db: FirebaseFirestore = Firebase.firestore,
) {
    private fun doc(uid: String) = db.collection(COLLECTION).document(uid)

    /** Crea el perfil si no existe; si existe, solo sincroniza isAnonymous (cambia al vincular Google). */
    suspend fun ensureProfile(user: FirebaseUser) {
        val ref = doc(user.uid)
        db.runTransaction { tx ->
            val snapshot = tx.get(ref)
            if (!snapshot.exists()) {
                tx.set(
                    ref,
                    mapOf(
                        FIELD_UID to user.uid,
                        FIELD_IS_ANONYMOUS to user.isAnonymous,
                        FIELD_WISHLIST to emptyList<String>(),
                        FIELD_SEARCH_HISTORY to emptyList<String>(),
                        FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                        FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                )
            } else if (snapshot.getBoolean(FIELD_IS_ANONYMOUS) != user.isAnonymous) {
                tx.update(ref, FIELD_IS_ANONYMOUS, user.isAnonymous, FIELD_UPDATED_AT, FieldValue.serverTimestamp())
            }
        }.await()
    }

    suspend fun getProfile(uid: String): UserProfile? = doc(uid).get().await().toUserProfileOrNull()

    /** Emite el perfil en tiempo real; null si aún no existe. */
    fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        val registration = doc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toUserProfileOrNull())
        }
        awaitClose { registration.remove() }
    }

    // arrayUnion/arrayRemove: sin leer antes, y sin pisar cambios hechos desde otro dispositivo.
    suspend fun addToWishlist(uid: String, dealId: String) {
        doc(uid).update(FIELD_WISHLIST, FieldValue.arrayUnion(dealId), FIELD_UPDATED_AT, FieldValue.serverTimestamp()).await()
    }

    suspend fun removeFromWishlist(uid: String, dealId: String) {
        doc(uid).update(FIELD_WISHLIST, FieldValue.arrayRemove(dealId), FIELD_UPDATED_AT, FieldValue.serverTimestamp()).await()
    }

    /** Pone la búsqueda al principio (sin duplicados) y conserva las [MAX_SEARCH_HISTORY] más recientes. */
    suspend fun recordSearch(uid: String, query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val ref = doc(uid)
        db.runTransaction { tx ->
            val current = tx.get(ref).stringList(FIELD_SEARCH_HISTORY)
            val updated = (listOf(normalized) + current.filterNot { it.equals(normalized, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY)
            tx.update(ref, FIELD_SEARCH_HISTORY, updated, FIELD_UPDATED_AT, FieldValue.serverTimestamp())
        }.await()
    }

    /**
     * Copia wishlist e historial de un perfil anónimo abandonado al perfil de [toUid]. Hay que leer
     * [from] antes de cambiar de sesión: después, las reglas ya no dejan leer el documento anónimo.
     */
    suspend fun mergeInto(from: UserProfile, toUid: String) {
        val ref = doc(toUid)
        db.runTransaction { tx ->
            val target = tx.get(ref)
            val history = (target.stringList(FIELD_SEARCH_HISTORY) + from.searchHistory)
                .distinctBy { it.lowercase() }
                .take(MAX_SEARCH_HISTORY)
            tx.update(
                ref,
                FIELD_WISHLIST, (target.stringList(FIELD_WISHLIST) + from.wishlist).distinct(),
                FIELD_SEARCH_HISTORY, history,
                FIELD_UPDATED_AT, FieldValue.serverTimestamp(),
            )
        }.await()
    }

    /** Mapeo manual y tolerante, como en DealsRepository: un campo mal formado no tumba el perfil. */
    private fun DocumentSnapshot.toUserProfileOrNull(): UserProfile? {
        if (!exists()) return null
        return UserProfile(
            uid = id,
            isAnonymous = getBoolean(FIELD_IS_ANONYMOUS) ?: true,
            wishlist = stringList(FIELD_WISHLIST),
            searchHistory = stringList(FIELD_SEARCH_HISTORY),
            createdAt = getTimestamp(FIELD_CREATED_AT)?.toDate()?.time,
            updatedAt = getTimestamp(FIELD_UPDATED_AT)?.toDate()?.time,
        ).also { if (getString(FIELD_UID) != id) Log.w(TAG, "Perfil $id con campo uid inconsistente") }
    }

    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>)?.filterIsInstance<String>().orEmpty()

    companion object {
        const val MAX_SEARCH_HISTORY = 20
        private const val TAG = "UserProfileRepository"
        private const val COLLECTION = "users"
        private const val FIELD_UID = "uid"
        private const val FIELD_IS_ANONYMOUS = "isAnonymous"
        private const val FIELD_WISHLIST = "wishlist"
        private const val FIELD_SEARCH_HISTORY = "searchHistory"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"
    }
}
