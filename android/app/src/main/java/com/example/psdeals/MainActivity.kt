package com.example.psdeals

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.psdeals.ui.deals.DealsScreen
import com.example.psdeals.ui.theme.PSDealsTheme

class MainActivity : ComponentActivity() {

    // Debe registrarse antes de que la Activity llegue a STARTED, por eso es una propiedad.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS concedido: $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en el primer arranque de la Activity, no en cada rotación.
        if (savedInstanceState == null) askNotificationPermissionIfNeeded()
        setContent {
            PSDealsTheme {
                DealsScreen()
            }
        }
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
