package co.golab.deals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import co.golab.deals.R
import co.golab.deals.data.AppPreferences
import co.golab.deals.data.StorePreferences
import co.golab.deals.model.StorePlatform
import co.golab.deals.ui.deals.iconRes

/**
 * Tiendas de las que llegan las ofertas del día y desde qué descuento. Se abre desde el perfil y desde ⓘ, así que
 * también lo tiene quien no inició sesión. Los cambios se aplican al guardar (cada cambio resincroniza los temas
 * de FCM).
 */
@Composable
fun StoreAlertsDialog(
    preferences: StorePreferences,
    onSave: (stores: Set<StorePlatform>, minDiscount: Int) -> Unit,
    onBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Ids y no el enum: rememberSaveable guarda listas de String sin un Saver propio.
    var storeIds by rememberSaveable { mutableStateOf(preferences.alertStores.map { it.id }) }
    var minDiscount by rememberSaveable { mutableIntStateOf(preferences.minDiscount) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_notification), contentDescription = null) },
        title = { Text("Tiendas y alertas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Te avisamos de las ofertas nuevas de:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                StorePlatform.entries.forEach { platform ->
                    val checked = platform.id in storeIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Toda la fila responde al toque y TalkBack la anuncia como un interruptor.
                            .toggleable(value = checked, role = Role.Switch) { on ->
                                storeIds = if (on) storeIds + platform.id else storeIds - platform.id
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(painterResource(platform.iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(platform.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = checked, onCheckedChange = null)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Avisarme desde", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                DiscountSelector(selected = minDiscount, onSelect = { minDiscount = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    if (storeIds.isEmpty()) {
                        "No recibirás las ofertas del día. Las alertas de tu lista de deseos siguen llegando."
                    } else {
                        "Las alertas de los juegos de tu lista de deseos llegan siempre, de cualquier tienda."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBatterySettings, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("¿No te llegan a tiempo? Revisa la batería")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(storeIds.mapNotNullTo(LinkedHashSet()) { StorePlatform.fromId(it) }, minDiscount)
                    onDismiss()
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Avisarme desde": -30 %, -50 %, -70 % o solo gratis. FlowRow: en pantallas angostas los chips bajan de línea. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscountSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppPreferences.DISCOUNT_OPTIONS.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(AppPreferences.discountLabel(option)) },
            )
        }
    }
}
