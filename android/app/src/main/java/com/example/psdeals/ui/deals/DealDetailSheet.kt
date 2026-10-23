package com.example.psdeals.ui.deals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.psdeals.R
import com.example.psdeals.model.Deal
import com.example.psdeals.model.StorePlatform
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ancho pedido a la CDN para la portada del panel: ocupa todo el ancho del teléfono, no media columna. */
private const val HERO_IMAGE_WIDTH = 1080
private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val HeroShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(16.dp)

/**
 * Detalle de una oferta antes de salir a la tienda. Solo dibuja y avisa: las analíticas y la redirección
 * las decide quien lo abre (DealsGrid), igual que con DealCard.
 * @param onGoToStore el botón principal; el panel se cierra después de llamarlo.
 * @param onToggleWishlist null oculta el botón de la lista de deseos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealDetailSheet(
    deal: Deal,
    onGoToStore: () -> Unit,
    onDismiss: () -> Unit,
    isExpired: Boolean = false,
    isWishlisted: Boolean = false,
    onToggleWishlist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    // Siempre a altura completa: a medio abrir el botón principal quedaría por debajo del borde.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val platform = StorePlatform.fromId(deal.platform)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Scrim suave: la grilla se intuye detrás, el foco queda en el panel.
        scrimColor = Color.Black.copy(alpha = 0.45f),
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Títulos largos o muchos géneros en pantallas pequeñas: el panel se desplaza en vez de cortar el botón.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            HeroImage(deal = deal, isExpired = isExpired)
            Spacer(Modifier.height(20.dp))

            if (platform != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(platform.iconRes),
                        contentDescription = null, // el texto de al lado ya la nombra
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        platform.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                deal.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            if (deal.genres.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                GenrePills(deal.genres)
            }

            Spacer(Modifier.height(24.dp))
            PriceBlock(deal = deal, isExpired = isExpired)

            Spacer(Modifier.height(20.dp))
            EndDateRow(endDate = deal.endDate, isExpired = isExpired)

            Spacer(Modifier.height(28.dp))
            StoreButton(
                platform = platform,
                isExpired = isExpired,
                onClick = {
                    onGoToStore()
                    // Se cierra mientras abre la tienda: al volver con "Atrás", el usuario cae en la grilla.
                    scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
                },
            )

            if (onToggleWishlist != null || onShare != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onToggleWishlist != null) {
                        SecondaryButton(
                            label = if (isWishlisted) "Guardado" else "Guardar",
                            iconRes = if (isWishlisted) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                            iconTint = if (isWishlisted) WishlistColor else MaterialTheme.colorScheme.onSurface,
                            onClick = onToggleWishlist,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (onShare != null) {
                        SecondaryButton(
                            label = "Compartir",
                            iconRes = R.drawable.ic_share,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            onClick = onShare,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Los precios y disponibilidad pueden variar. El precio final es el de la tienda oficial.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HeroImage(deal: Deal, isExpired: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(HeroShape),
    ) {
        AsyncImage(
            model = deal.imageUrl?.let { resized(it, HERO_IMAGE_WIDTH) },
            contentDescription = deal.title,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.matchParentSize(),
        )
        // Velo inferior: da profundidad y separa la portada del fondo del panel en portadas claras.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))),
        )
        val badgeModifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
        if (isExpired) ExpiredBadge(badgeModifier) else DiscountBadge(percent = deal.discountPercent, modifier = badgeModifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePills(genres: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Text(
                genre,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PriceBlock(deal: Deal, isExpired: Boolean) {
    Column {
        Text(
            if (isExpired) "ÚLTIMO PRECIO EN OFERTA" else "PRECIO EN OFERTA",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                deal.discountedPriceText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                // Terminada: el precio es solo referencia, sin el color de acento.
                color = if (isExpired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                deal.originalPriceText,
                style = MaterialTheme.typography.bodyLarge,
                // Mismo criterio que en la tarjeta: onSurface con alpha 0.7 mantiene AA en modo claro.
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** Fecha de fin con cuenta regresiva; en rojo cuando queda menos de un día. */
@Composable
private fun EndDateRow(endDate: Long?, isExpired: Boolean) {
    val now = System.currentTimeMillis()
    val remaining = endDate?.minus(now)
    val urgent = !isExpired && remaining != null && remaining in 1 until TimeUnit.DAYS.toMillis(1)
    val (title, subtitle) = when {
        isExpired || (remaining != null && remaining <= 0) ->
            "Esta oferta terminó" to "El precio mostrado es el último que vimos en la tienda."
        endDate == null -> "Sin fecha de fin publicada" to "La tienda no indica hasta cuándo dura la rebaja."
        else -> remainingText(remaining!!) to "Hasta el ${END_DATE_FORMAT.get()!!.format(Date(endDate))}"
    }
    // error y no el rojo de la insignia: el tema lo aclara en modo oscuro (el rojo de la insignia quedaba en ~2.5:1).
    val accent = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_schedule),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (urgent) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Botón principal con el color de la tienda (el mismo de los chips: ≥ 7:1 con blanco). El borde claro lo
 * despega del panel en modo oscuro, donde el gris de Epic casi se funde con el fondo.
 */
@Composable
private fun StoreButton(platform: StorePlatform?, isExpired: Boolean, onClick: () -> Unit) {
    val store = platform?.label ?: "la tienda"
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = platform?.brandColor ?: MaterialTheme.colorScheme.primary,
            contentColor = if (platform != null) Color.White else MaterialTheme.colorScheme.onPrimary,
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (platform != null) {
            Icon(painterResource(platform.iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            if (isExpired) "Ver en $store" else "Ir a $store",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SecondaryButton(label: String, iconRes: Int, iconTint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = ButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(48.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun remainingText(remaining: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    val days = TimeUnit.MILLISECONDS.toDays(remaining)
    return when {
        hours < 1 -> "Termina en menos de 1 hora"
        days < 1 -> "Termina en $hours h"
        days == 1L -> "Queda 1 día"
        else -> "Quedan $days días"
    }
}

/** "jueves 30 de octubre, 11:59 p. m."; SimpleDateFormat no es thread-safe, de ahí el ThreadLocal. */
private val END_DATE_FORMAT = ThreadLocal.withInitial {
    SimpleDateFormat("EEEE d 'de' MMMM, h:mm a", Locale.forLanguageTag("es-CO"))
}
