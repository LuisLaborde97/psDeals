package co.golab.deals.data

import android.util.Log
import androidx.core.net.toUri
import co.golab.deals.BuildConfig
import co.golab.deals.model.AppUpdate
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen

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
                // Ej. RESOURCE_EXHAUSTED (cuota diaria agotada) o PERMISSION_DENIED. Firestore da por terminado el
                // listener tras un error: retryWhen lo vuelve a crear, si no el aviso no aparecía hasta reiniciar la app.
                Log.w(TAG, "No se pudo leer config/$UPDATE_DOC", error)
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toAppUpdateOrNull())
        }
        awaitClose { registration.remove() }
    }.retryWhen { _, attempt ->
        emit(null) // mientras tanto, sin aviso: un fallo aquí nunca debe impedir usar la app
        delay(minOf(RETRY_BASE_MS shl attempt.toInt().coerceAtMost(4), RETRY_MAX_MS))
        true
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
            latestVersionName = getString("latestVersionName")?.trim()?.takeIf { it.isNotEmpty() },
            downloadUrl = url,
            // La consola de Firestore guarda "\n" literal al escribir en una sola línea.
            releaseNotes = getString("releaseNotes").orEmpty().replace("\\n", "\n").trim(),
            isMandatory = getBoolean("isMandatory") ?: false,
        )
    }

    private companion object {
        const val TAG = "AppConfigRepository"
        const val COLLECTION = "config"
        /** Reintentos a 30 s, 1, 2, 4 y luego cada 8 min. */
        const val RETRY_BASE_MS = 30_000L
        const val RETRY_MAX_MS = 8 * 60_000L

        /**
         * Las compilaciones debug leen su propio documento: así se puede probar el aviso (incluso uno
         * obligatorio) sin que les salga a los usuarios del alpha, que usan la release y leen `update`.
         */
        val UPDATE_DOC = if (BuildConfig.DEBUG) "update_debug" else "update"
    }
}
