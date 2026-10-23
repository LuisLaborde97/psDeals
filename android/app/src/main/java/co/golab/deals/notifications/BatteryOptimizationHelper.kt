package co.golab.deals.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Ayuda a que el sistema no frene las alertas de ofertas en segundo plano.
 *
 * Sin el permiso REQUEST_IGNORE_BATTERY_OPTIMIZATIONS a propósito: Google Play solo lo admite en apps cuya función
 * principal no puede funcionar sin él (llamadas, alarmas…), y las alertas ya llegan como push de FCM de prioridad
 * alta, que atraviesa Doze. Lo que sí las frena en capas como MIUI/HyperOS es el ahorro de batería del fabricante:
 * por eso se abre la ficha de la app en Ajustes, donde está "Batería → Sin restricciones" (en HyperOS, "Encendido").
 * Elegirla allí también exime a la app de la optimización de Android, así que [isIgnoringBatteryOptimizations] lo detecta.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimization"
    private const val PREFS = "battery_optimization"
    private const val KEY_PROMPT_SHOWN = "prompt_shown"

    /** Xiaomi, Redmi y POCO: sus opciones se llaman distinto y la UI explica dónde tocar. */
    val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /** La app ya está exenta de la optimización de batería de Android. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** El aviso se muestra una sola vez; después queda la opción en el perfil. */
    fun shouldShowPrompt(context: Context): Boolean =
        !isIgnoringBatteryOptimizations(context) && !prefs(context).getBoolean(KEY_PROMPT_SHOWN, false)

    fun markPromptShown(context: Context) = prefs(context).edit { putBoolean(KEY_PROMPT_SHOWN, true) }

    /**
     * Abre la ficha de la app en Ajustes. Si el fabricante la bloquea, cae a la lista general de optimización de
     * batería, donde el usuario tiene que buscar la app.
     */
    fun openBatterySettings(context: Context) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        val batteryList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(appDetails, batteryList)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No se pudo abrir ${intent.action}", e)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
