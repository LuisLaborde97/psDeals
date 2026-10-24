package co.golab.deals.ui.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import co.golab.deals.R
import co.golab.deals.model.AppUpdate

/**
 * Descarga la APK con el gestor de descargas de Android en vez del navegador: abierto desde la app, el navegador
 * (p. ej. Brave) usa una pestaña propia donde no aparece su confirmación de "descargar de todos modos", y la
 * descarga se queda trabada al 100 % o se cancela al cerrarla.
 *
 * La app no abre el instalador: eso exigiría REQUEST_INSTALL_PACKAGES, restringido en Google Play. El usuario
 * toca la notificación de descarga y Android lo abre. Solo para la distribución por GitHub: en Play, las
 * actualizaciones las hace Play (su política prohíbe que una app se actualice con APKs propias).
 */
object UpdateDownloader {
    private const val TAG = "UpdateDownloader"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * @return false si no se pudo encolar: quien llama debe abrir el enlace en el navegador. Pasa en Android 9 o
     *   anterior, donde guardar en Descargas exige el permiso de almacenamiento.
     */
    fun start(context: Context, update: AppUpdate): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val appName = context.getString(R.string.app_name)
        val version = update.latestVersionName ?: update.latestVersionCode.toString()
        val request = DownloadManager.Request(update.downloadUrl.toUri())
            .setTitle("$appName $version")
            .setDescription("Toca para instalar")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "GoLabDeals-$version.apk")
        return try {
            manager.enqueue(request)
            true
        } catch (e: Exception) {
            // IllegalArgumentException (URL rara) o SecurityException de algunas capas del fabricante.
            Log.w(TAG, "No se pudo iniciar la descarga", e)
            false
        }
    }

    /** Lista de descargas del sistema, por si el usuario descartó la notificación. */
    fun openDownloads(context: Context) {
        try {
            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No hay app de descargas", e)
        }
    }
}
