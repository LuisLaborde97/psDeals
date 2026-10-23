package co.golab.deals

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import co.golab.deals.notifications.NotificationTap
import co.golab.deals.ui.navigation.AppNavigation
import co.golab.deals.ui.openStoreUrl
import co.golab.deals.ui.theme.PSDealsTheme

class MainActivity : ComponentActivity() {

    // Debe registrarse antes de que la Activity llegue a STARTED, por eso es una propiedad.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS concedido: $granted")
        }

    /** Pedido de abrir la pestaña Favoritos (alerta de wishlist); AppNavigation lo atiende y lo apaga. */
    private var openWishlistRequest by mutableStateOf(false)

    /**
     * Las ofertas ya llegaron (o falló la carga): la splash puede irse. Lo marca DealsScreen; el
     * ViewModel vive dentro de NavHost y la Activity no lo ve. Volatile: se escribe desde la composición
     * y se lee en el pre-draw de cada cuadro.
     */
    @Volatile
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate(): cambia del tema de arranque (Theme.PSDeals.Starting) al de la app.
        val splash = installSplashScreen()
        // Solo en un arranque real. Al recrearse (rotación) la splash no se muestra, pero la condición sí
        // bloquearía el dibujo: estando en Favoritos, DealsScreen no avisaría y la app se congelaría hasta el timeout.
        if (savedInstanceState == null) {
            val start = SystemClock.uptimeMillis()
            // Se evalúa en cada cuadro mientras la splash está visible: solo lecturas baratas.
            // El tope garantiza que sin conexión (o con Firestore lento) la app se muestre de todos modos.
            splash.setKeepOnScreenCondition { !contentReady && SystemClock.uptimeMillis() - start < SPLASH_TIMEOUT_MS }
        }
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
                    onDealsReady = { contentReady = true },
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
        /**
         * Más que esto, una splash fija parece un cuelgue: mejor mostrar la app con su indicador de carga.
         * Medido en un POCO X8 Pro: en frío, Firestore entrega las ofertas a ~2,2 s del arranque.
         */
        const val SPLASH_TIMEOUT_MS = 2_500L
    }
}
