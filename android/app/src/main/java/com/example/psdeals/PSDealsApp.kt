package com.example.psdeals

import android.app.Application
import android.util.Log
import com.example.psdeals.data.UserSession
import com.example.psdeals.notifications.DealsNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * El canal se crea aquí y no en MainActivity porque el servicio FCM puede arrancar
 * (y recibir un push) sin que ninguna Activity se haya abierto.
 */
class PSDealsApp : Application() {
    /** Vive lo mismo que el proceso: la sesión no depende de ninguna pantalla. */
    private val appScope = CoroutineScope(SupervisorJob())

    val userSession by lazy { UserSession() }

    override fun onCreate() {
        super.onCreate()
        DealsNotifications.createChannel(this)
        DealsNotifications.subscribeToTopic()
        // En segundo plano y sin bloquear la UI: las ofertas se leen igual mientras tanto. Si falla
        // (sin conexión), se reintenta en el próximo arranque; la sesión ya creada persiste en disco.
        appScope.launch {
            runCatching { userSession.start() }
                .onSuccess { Log.d(TAG, "Sesión lista (anónima=${it.isAnonymous})") }
                .onFailure { Log.w(TAG, "No se pudo iniciar la sesión", it) }
        }
    }

    private companion object {
        const val TAG = "PSDealsApp"
    }
}
