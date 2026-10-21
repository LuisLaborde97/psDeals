package com.example.psdeals.data

import android.content.Context
import androidx.core.content.edit

/**
 * Recuerda qué token de FCM se registró y en qué uid. Cuando FCM rota el token, el viejo deja de servir:
 * sin este registro local no habría forma de saber cuál quitar de Firestore, y fcmTokens crecería con
 * tokens muertos.
 */
class PushTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val lastToken: String? get() = prefs.getString(KEY_TOKEN, null)
    val lastUid: String? get() = prefs.getString(KEY_UID, null)

    fun save(uid: String, token: String) = prefs.edit {
        putString(KEY_UID, uid)
        putString(KEY_TOKEN, token)
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val PREFS = "push_tokens"
        const val KEY_TOKEN = "token"
        const val KEY_UID = "uid"
    }
}
