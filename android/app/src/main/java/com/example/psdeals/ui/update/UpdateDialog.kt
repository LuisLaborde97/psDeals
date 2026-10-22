package com.example.psdeals.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.psdeals.R
import com.example.psdeals.model.AppUpdate
import com.example.psdeals.ui.openStoreUrl

/**
 * Aviso de versión nueva. Obligatoria: sin "Más tarde" y sin cerrarse con Atrás ni tocando fuera, así la app
 * no se usa sin actualizar. "Actualizar" no cierra el diálogo: si el usuario vuelve sin instalar, sigue ahí.
 */
@Composable
fun UpdateDialog(update: AppUpdate, onPostpone: () -> Unit) {
    val context = LocalContext.current
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
                    Text(update.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "Se descargará el instalable en tu navegador. Ábrelo desde la notificación de descarga para instalarlo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = { context.openStoreUrl(update.downloadUrl) }) { Text("Actualizar") } },
        dismissButton = if (update.isMandatory) null else ({ TextButton(onClick = onPostpone) { Text("Más tarde") } }),
        // Mismo estilo que los diálogos de monedas y de cuenta.
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
