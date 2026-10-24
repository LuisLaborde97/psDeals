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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import co.golab.deals.R
import co.golab.deals.model.AppUpdate
import co.golab.deals.ui.openStoreUrl

/**
 * Aviso de versión nueva. Obligatoria: sin "Más tarde" y sin cerrarse con Atrás ni tocando fuera, así la app
 * no se usa sin actualizar. "Actualizar" no cierra el diálogo: si el usuario vuelve sin instalar, sigue ahí.
 */
@Composable
fun UpdateDialog(update: AppUpdate, onPostpone: () -> Unit) {
    val context = LocalContext.current
    // Ya encolada: el botón pasa a abrir la lista de descargas en vez de bajar el archivo otra vez.
    var downloading by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!update.isMandatory) onPostpone() },
        properties = DialogProperties(
            dismissOnBackPress = !update.isMandatory,
            dismissOnClickOutside = !update.isMandatory,
        ),
        title = {
            Text(
                if (update.isMandatory) "Actualización necesaria" else "Nueva versión disponible",
                textAlign = TextAlign.Center,
            )
        },
        text = {
            // Notas largas: con scroll propio para que los botones no queden fuera de la pantalla.
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (update.isMandatory) {
                    Text(
                        "Esta versión ya no es compatible. Actualiza para seguir usando ${stringResource(id = R.string.app_name)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (update.releaseNotes.isNotEmpty()) {
                    // Seleccionable: las notas pueden traer un enlace o instrucciones que el usuario quiera copiar.
                    SelectionContainer {
                        Text(update.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    if (downloading) {
                        "Descargando… Cuando termine, toca la notificación de descarga para instalar la nueva versión."
                    } else {
                        "Se descargará el instalador. Cuando termine, toca la notificación de descarga para instalarlo."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                DownloadFallback(url = update.downloadUrl)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        downloading -> UpdateDownloader.openDownloads(context)
                        UpdateDownloader.start(context, update) -> downloading = true
                        else -> context.openStoreUrl(update.downloadUrl) // Android 9 o anterior: navegador
                    }
                },
            ) { Text(if (downloading) "Ver descargas" else "Actualizar") }
        },
        dismissButton = if (update.isMandatory) null else ({ TextButton(onClick = onPostpone) { Text("Más tarde") } }),
        // Mismo estilo que los diálogos de monedas y de cuenta.
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
