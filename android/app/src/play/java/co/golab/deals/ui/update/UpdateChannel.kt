package co.golab.deals.ui.update

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import co.golab.deals.model.AppUpdate
import co.golab.deals.ui.openStoreUrl

// Canal Google Play: la política de Play prohíbe que la app se actualice con APKs propias, así que el aviso
// (config/update_play) solo lleva a la ficha de la app en Play Store, que hace la actualización.

@Composable
internal fun UpdateInstructions(update: AppUpdate, started: Boolean) {
    Text(
        "Se abrirá Google Play para que actualices la app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Siempre la ficha de esta app (no el enlace del documento): no hay riesgo de mandar a otra parte. */
@Composable
internal fun UpdateConfirmButton(update: AppUpdate, started: Boolean, onStarted: () -> Unit) {
    val context = LocalContext.current
    Button(
        onClick = {
            context.openStoreUrl("https://play.google.com/store/apps/details?id=${context.packageName}")
            onStarted()
        },
    ) { Text("Actualizar en Google Play") }
}
