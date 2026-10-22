package com.example.psdeals.data

import android.util.Log
import androidx.core.net.toUri
import com.example.psdeals.BuildConfig
import com.example.psdeals.model.AppUpdate
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Configuración remota de la app en la colección `config` (de solo lectura para la app; ver firestore.rules).
 * La regla `config/{docId}` ya cubre `update_debug`: no hace falta tocar las reglas.
 */
class AppConfigRepository(
    private val db: FirebaseFirestore = Firebase.firestore,
) {
    /**
     * Última versión publicada, en tiempo real: quien tenga la app abierta ve el aviso apenas se edita el
     * documento. null si no existe, está mal formado o no se pudo leer: un fallo aquí nunca debe impedir
     * usar la app.
     */
    fun observeUpdate(): Flow<AppUpdate?> = callbackFlow {
        val registration = db.collection(COLLECTION).document(UPDATE_DOC).addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Ej. PERMISSION_DENIED si las reglas aún no permiten leer `config`.
                Log.w(TAG, "No se pudo leer config/$UPDATE_DOC", error)
                trySend(null)
                close()
                return@addSnapshotListener
            }
            trySend(snapshot?.toAppUpdateOrNull())
        }
        awaitClose { registration.remove() }
    }

    /** Mapeo manual y tolerante, como en DealsRepository: un documento mal escrito en la consola se ignora. */
    private fun DocumentSnapshot.toAppUpdateOrNull(): AppUpdate? {
        if (!exists()) return null
        val versionCode = (get("latestVersionCode") as? Number)?.toInt()
        // Solo enlaces web: el documento se edita a mano y un esquema raro no debe lanzar cualquier intent.
        val url = getString("downloadUrl")?.trim()?.takeIf { it.toUri().scheme in setOf("https", "http") }
        if (versionCode == null || url == null) {
            Log.w(TAG, "config/$UPDATE_DOC ignorado: falta latestVersionCode (número) o downloadUrl (http/https)")
            return null
        }
        return AppUpdate(
            latestVersionCode = versionCode,
            downloadUrl = url,
            // La consola de Firestore guarda "\n" literal al escribir en una sola línea.
            releaseNotes = getString("releaseNotes").orEmpty().replace("\\n", "\n").trim(),
            isMandatory = getBoolean("isMandatory") ?: false,
        )
    }

    private companion object {
        const val TAG = "AppConfigRepository"
        const val COLLECTION = "config"

        /**
         * Las compilaciones debug leen su propio documento: así se puede probar el aviso (incluso uno
         * obligatorio) sin que les salga a los usuarios del alpha, que usan la release y leen `update`.
         */
        val UPDATE_DOC = if (BuildConfig.DEBUG) "update_debug" else "update"
    }
}
