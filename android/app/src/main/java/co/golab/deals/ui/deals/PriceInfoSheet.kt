package co.golab.deals.ui.deals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.golab.deals.R
import co.golab.deals.model.StorePlatform
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val CardShape = RoundedCornerShape(20.dp)

/** Moneda en que cobra cada tienda en Colombia y la aclaración que la acompaña. */
private data class StoreCurrency(val platform: StorePlatform, val currency: String, val detail: String)

// Epic: las rebajas vienen de IsThereAnyDeal, que para Colombia solo da el precio de EE. UU. en USD.
private val STORE_CURRENCIES = listOf(
    StoreCurrency(StorePlatform.PLAYSTATION, "US$", "Cobra en dólares estadounidenses."),
    StoreCurrency(StorePlatform.STEAM, "COP", "Cobra en pesos colombianos."),
    StoreCurrency(StorePlatform.XBOX, "COP", "Cobra en pesos colombianos."),
    StoreCurrency(
        StorePlatform.EPIC,
        "US$ · COP",
        "Juegos gratis en pesos. En las rebajas mostramos como referencia el precio en dólares de EE. UU.; " +
            "lo que Epic te cobre en pesos puede ser distinto.",
    ),
)

/**
 * Información de precios (ícono ⓘ): moneda de cada tienda, sin comisiones y aviso de no afiliación. Panel
 * deslizable con el mismo lenguaje visual que la cuenta y el detalle de ofertas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceInfoSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val appName = stringResource(R.string.app_name)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Precios y monedas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Cada tienda publica sus precios para Colombia en su propia moneda, y los mostramos tal cual.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CardShape)
                    .padding(16.dp),
            ) {
                STORE_CURRENCIES.forEach { StoreCurrencyRow(it) }
            }

            Spacer(Modifier.height(12.dp))
            InfoCard(
                iconRes = R.drawable.ic_local_offer,
                title = "Sin comisiones",
                text = "$appName es gratis y no cobra nada. Si pagas en dólares, el cargo final depende de la tasa de " +
                    "cambio de tu banco.",
            )
            Spacer(Modifier.height(12.dp))
            InfoCard(
                iconRes = R.drawable.ic_info,
                title = "Precios de referencia",
                text = "Mostramos el último precio que vimos en cada tienda; puede cambiar. El precio final es el de la " +
                    "tienda oficial.",
            )
            Spacer(Modifier.height(12.dp))
            // Uso nominativo de las marcas: identifica cada tienda sin sugerir que alguna avala la app.
            InfoCard(
                iconRes = R.drawable.ic_check,
                title = "Proyecto independiente",
                text = "$appName no está afiliado a PlayStation, Steam, Epic Games ni Xbox. Sus nombres, logotipos y " +
                    "portadas pertenecen a sus dueños.",
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text("Entendido", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * Logo sobre el color de la tienda; al lado, el nombre con la moneda como etiqueta y debajo la aclaración, a todo el
 * ancho (la de Epic es larga). El borde claro despega el gris de Epic del fondo de la tarjeta.
 */
@Composable
private fun StoreCurrencyRow(item: StoreCurrency) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(item.platform.brandColor, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
        ) {
            Icon(painterResource(item.platform.iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.platform.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    item.currency,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoCard(iconRes: Int, title: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, CardShape)
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
