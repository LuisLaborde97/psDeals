package co.golab.deals.ui.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import co.golab.deals.model.AppUpdate
import co.golab.deals.ui.openStoreUrl

// Canal GitHub Releases: la app descarga la APK nueva (UpdateDownloader) y ofrece el enlace como respaldo.

@Composable
internal fun UpdateInstructions(update: AppUpdate, started: Boolean) {
    Text(
        if (started) {
            "Descargando… Cuando termine, toca la notificación de descarga para instalar la nueva versión."
        } else {
            "Se descargará el instalador. Cuando termine, toca la notificación de descarga para instalarlo."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    DownloadFallback(url = update.downloadUrl)
}

/** Ya encolada, el botón abre la lista de descargas en vez de bajar el archivo otra vez. */
@Composable
internal fun UpdateConfirmButton(update: AppUpdate, started: Boolean, onStarted: () -> Unit) {
    val context = LocalContext.current
    Button(
        onClick = {
            when {
                started -> UpdateDownloader.openDownloads(context)
                UpdateDownloader.start(context, update) -> onStarted()
                else -> context.openStoreUrl(update.downloadUrl) // Android 9 o anterior: navegador
            }
        },
    ) { Text(if (started) "Ver descargas" else "Actualizar") }
}

/**
 * Plan B si la descarga no avanza (gestor de descargas desactivado, capa del fabricante, Android 9 o anterior):
 * el enlace a la vista y seleccionable, para copiarlo o abrirlo en el navegador.
 */
@Composable
private fun DownloadFallback(url: String) {
    val context = LocalContext.current
    Column {
        Text(
            "¿La descarga no avanza? Usa este enlace:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { copyToClipboard(context, url) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Copiar enlace")
            }
            TextButton(onClick = { openInBrowser(context, url) }) { Text("Abrir en el navegador") }
        }
    }
}

private fun copyToClipboard(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Enlace de GoLab Deals", url))
    // Android 13+ ya muestra su propia confirmación al copiar.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Abre el enlace en el navegador predeterminado, en su propia tarea (NEW_TASK) para que al volver con "Atrás" el
 * usuario caiga en la app. Brave lo abre en su pestaña reducida, pero ahí sí pregunta dónde guardar la descarga.
 */
private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay un navegador instalado", Toast.LENGTH_SHORT).show()
    }
}
