package com.example.psdeals

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.psdeals.notifications.NotificationTap
import com.example.psdeals.ui.navigation.AppNavigation
import com.example.psdeals.ui.openStoreUrl
import com.example.psdeals.ui.theme.PSDealsTheme

class MainActivity : ComponentActivity() {

    // Debe registrarse antes de que la Activity llegue a STARTED, por eso es una propiedad.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS concedido: $granted")
        }

    /** Pedido de abrir la pestaña Favoritos (alerta de wishlist); AppNavigation lo atiende y lo apaga. */
    private var openWishlistRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate(): cambia del tema de arranque (Theme.PSDeals.Starting) al de la app.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en el primer arranque de la Activity, no en cada rotación: al recrearse (rotación, cambio de
        // tema, restauración tras matar el proceso) Android vuelve a entregar el mismo intent, y savedInstanceState
        // es la señal de que ya se atendió.
        if (savedInstanceState == null) {
            askNotificationPermissionIfNeeded()
            handleNotificationTap(intent)
        }
        setContent {
            PSDealsTheme {
                AppNavigation(
                    openWishlistRequest = openWishlistRequest,
                    onOpenWishlistHandled = { openWishlistRequest = false },
                )
            }
        }
    }

    /** App ya abierta (launchMode singleTop): el toque llega aquí y no a onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent) {
        // Abrir desde Recientes reentrega el intent original de la tarea: no es un toque nuevo.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val tap = NotificationTap.parse(intent) ?: return
        // Se consume antes de actuar: getIntent() ya no lleva la marca del push, así que nada que lo relea
        // (recreación, otra llamada) lo dispara dos veces.
        setIntent(NotificationTap.consumed(intent))
        // Primero la pestaña y luego la tienda: al volver con "Atrás", el usuario cae en su lista de deseos.
        if (tap.openWishlist) openWishlistRequest = true
        openStoreUrl(tap.url)
    }

    /** Android 13+ (API 33) exige pedir este permiso en tiempo de ejecución; antes se concede al instalar. */
    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
