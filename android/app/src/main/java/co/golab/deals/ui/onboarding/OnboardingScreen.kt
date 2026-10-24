package co.golab.deals.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.golab.deals.R
import co.golab.deals.data.AppPreferences
import co.golab.deals.data.CountryDetector
import co.golab.deals.model.Country
import co.golab.deals.model.StorePlatform
import co.golab.deals.ui.CountrySelector
import co.golab.deals.ui.DiscountSelector
import co.golab.deals.ui.deals.brandColor
import co.golab.deals.ui.deals.iconRes
import co.golab.deals.ui.deals.rememberBackgroundBrush

/**
 * Bienvenida (una sola vez): dónde juega el usuario y desde qué descuento quiere alertas. Sus tiendas quedan como
 * filtro inicial y como las únicas de las que llegan las ofertas del día. "Omitir" deja todas desde -50 %, lo mismo
 * que recibían las versiones anteriores.
 *
 * @param initialCountry el detectado por la SIM o la región del teléfono (CountryDetector).
 * @param unsupportedCountryIso país detectado que aún no tiene ofertas: se avisa que se muestra otro.
 */
@Composable
fun OnboardingScreen(
    initialCountry: Country,
    unsupportedCountryIso: String?,
    onDone: (country: Country, stores: Set<StorePlatform>, minDiscount: Int) -> Unit,
) {
    var countryCode by rememberSaveable { mutableStateOf(initialCountry.code) }
    val country = Country.fromCode(countryCode) ?: initialCountry
    var storeIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var minDiscount by rememberSaveable { mutableIntStateOf(AppPreferences.DEFAULT_MIN_DISCOUNT) }
    // Fuera de un Scaffold o Surface nadie fija LocalContentColor: los textos sin color explícito salían negros sobre
    // el degradado oscuro.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(rememberBackgroundBrush()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Bienvenido a ${stringResource(R.string.app_name)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cuéntanos dónde juegas: verás primero esas ofertas y solo te avisaremos de ellas.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))
                Text("Tu país", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                CountrySelector(selected = country, onSelect = { countryCode = it.code })
                if (unsupportedCountryIso != null) {
                    Text(
                        "Aún no tenemos ofertas para ${CountryDetector.displayName(unsupportedCountryIso)}. " +
                            "Elige el país de tus cuentas en las tiendas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text("¿Dónde juegas?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                StorePlatform.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { platform ->
                            StoreCard(
                                platform = platform,
                                selected = platform.id in storeIds,
                                onClick = { storeIds = if (platform.id in storeIds) storeIds - platform.id else storeIds + platform.id },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(20.dp))
                Text("Avisarme de ofertas desde", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                DiscountSelector(selected = minDiscount, onSelect = { minDiscount = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Las alertas de los juegos que guardes en tu lista de deseos te llegan siempre, de cualquier tienda. " +
                        "Puedes cambiarlo cuando quieras en Ajustes (⚙).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { onDone(country, storeIds.mapNotNullTo(LinkedHashSet()) { StorePlatform.fromId(it) }, minDiscount) },
                    enabled = storeIds.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        if (storeIds.isEmpty()) "Elige al menos una tienda" else "Empezar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(
                    onClick = { onDone(country, StorePlatform.entries.toSet(), AppPreferences.DEFAULT_MIN_DISCOUNT) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Omitir por ahora") }
            }
        }
    }
}

/** Tarjeta de tienda: al elegirla se rellena con su color de marca (los mismos de los chips, ≥ 7:1 con blanco). */
@Composable
private fun StoreCard(platform: StorePlatform, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(96.dp)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) platform.brandColor else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
            contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Icon(painterResource(platform.iconRes), contentDescription = null, modifier = Modifier.size(28.dp))
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp),
                )
            }
            Text(
                platform.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}
