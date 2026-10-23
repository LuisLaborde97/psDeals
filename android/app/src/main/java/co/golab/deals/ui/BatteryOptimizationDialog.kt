package co.golab.deals.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import co.golab.deals.R
import co.golab.deals.notifications.BatteryOptimizationHelper

/** Explica por qué conviene quitar las restricciones de batería y lleva a la ficha de la app en Ajustes. */
@Composable
fun BatteryOptimizationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Cada capa nombra distinto las opciones: se dice exactamente qué tocar al llegar a Ajustes. En HyperOS la
    // entrada de batería se llama "Encendido" y lleva a "Ahorro de batería → Sin restricciones".
    val steps = if (BatteryOptimizationHelper.isXiaomi) {
        "En la siguiente pantalla toca \"Encendido\" (o \"Batería\") y elige \"Sin restricciones\"."
    } else {
        "En la siguiente pantalla entra a \"Batería\" (o \"Uso de batería de la app\") y elige \"Sin restricciones\"."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_notification), contentDescription = null) },
        title = { Text("Recibe las ofertas a tiempo") },
        text = {
            Text(
                "Para ahorrar batería, tu teléfono puede cerrar GoLab Deals en segundo plano y retrasar o bloquear " +
                    "las alertas de ofertas y de tu lista de deseos.\n\n$steps",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    BatteryOptimizationHelper.openBatterySettings(context)
                    onDismiss()
                },
            ) { Text("Configurar \"Sin restricción\"") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Más tarde") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
