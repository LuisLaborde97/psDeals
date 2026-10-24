package co.golab.deals

import android.app.Application
import android.util.Log
import co.golab.deals.data.AppPreferences
import co.golab.deals.data.PushTokenStore
import co.golab.deals.data.UserSession
import co.golab.deals.notifications.DealsNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * El canal se crea aquí y no en MainActivity porque el servicio FCM puede arrancar
 * (y recibir un push) sin que ninguna Activity se haya abierto.
 */
class PSDealsApp : Application() {
    /** Vive lo mismo que el proceso: la sesión no depende de ninguna pantalla. */
    private val appScope = CoroutineScope(SupervisorJob())

    val userSession by lazy { UserSession(pushTokens = PushTokenStore(this)) }

    val preferences by lazy { AppPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        DealsNotifications.createChannels(this)
        // Al arrancar y cada vez que el usuario cambia sus tiendas o su umbral de alertas.
        appScope.launch {
            preferences.state
                .map { it.alertStores to it.minDiscount }
                .distinctUntilChanged()
                .collect { DealsNotifications.syncTopics(this@PSDealsApp, preferences.state.value) }
        }
        // En segundo plano y sin bloquear la UI: las ofertas se leen igual mientras tanto. Si falla
        // (sin conexión), se reintenta en el próximo arranque; la sesión ya creada persiste en disco.
        appScope.launch {
            runCatching { userSession.start() }
                .onSuccess { Log.d(TAG, "Sesión lista (anónima=${it.isAnonymous})") }
                .onFailure { Log.w(TAG, "No se pudo iniciar la sesión", it) }
        }
    }

    /** Token o registro de FCM nuevo: las suscripciones a temas se vuelven a hacer aunque la elección no cambió. */
    fun resyncTopics() = DealsNotifications.syncTopics(this, preferences.state.value, force = true)

    /** FCM rotó el token (lo llama DealsMessagingService, que no tiene scope propio para la escritura). */
    fun onNewPushToken(token: String) {
        appScope.launch {
            runCatching { userSession.registerPushToken(token) }
                .onFailure { Log.w(TAG, "No se pudo registrar el token nuevo", it) }
        }
    }

    private companion object {
        const val TAG = "PSDealsApp"
    }
}
