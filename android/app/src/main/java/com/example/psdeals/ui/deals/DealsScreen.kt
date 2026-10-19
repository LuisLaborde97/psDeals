package com.example.psdeals.ui.deals

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.psdeals.R
import com.example.psdeals.model.Deal
import com.example.psdeals.ui.theme.PSDealsTheme

/** Rojo sobrio, llamativo sin ser chillón. Blanco encima = 4.97:1 (cumple WCAG AA para texto normal). */
private val DiscountBadgeColor = Color(0xFFD62839)
private const val SUPER_DEAL_THRESHOLD = 70

// Degradado de fondo inspirado en el logo: azul del control arriba, brasa del fuego abajo.
private val DarkGradient = listOf(Color(0xFF081020), Color(0xFF0E1426), Color(0xFF1E0606))
// Versión clara (azul hielo → melocotón) para que el texto oscuro del tema claro siga siendo legible.
private val LightGradient = listOf(Color(0xFFE8F0FF), Color(0xFFF4F1F8), Color(0xFFFFECE6))

/** Elige el degradado según el tema activo (incluye colores dinámicos), no solo según el sistema. */
@Composable
private fun rememberBackgroundBrush(): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(isDark) { Brush.verticalGradient(if (isDark) DarkGradient else LightGradient) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(viewModel: DealsViewModel = viewModel(factory = DealsViewModel.Factory)) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val genres by viewModel.availableGenres.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // El degradado ocupa toda la pantalla (también detrás de la barra de estado, por edge-to-edge);
    // Scaffold y TopAppBar son transparentes para dejarlo ver.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(rememberBackgroundBrush())
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        // Obligatorio con fondo transparente: contentColorFor(Transparent) no encuentra pareja en el tema y
        // cae al LocalContentColor por defecto (negro), dejando el texto heredado ilegible en modo oscuro.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = null, // decorativo: el título de al lado ya nombra la app
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("PSDeals", fontWeight = FontWeight.Bold)
                            (uiState as? DealsUiState.Success)?.let {
                                Text(
                                    if (it.isFiltered) "${it.deals.size} de ${it.totalCount} ofertas"
                                    else "${it.totalCount} juegos en oferta",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val bottomInset = innerPadding.calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            SearchField(query = viewModel.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            if (genres.isNotEmpty()) {
                GenreChips(genres = genres, selectedGenre = selectedGenre, onGenreClick = viewModel::onGenreClick)
            }

            when (val state = uiState) {
                DealsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                // PullToRefreshBox necesita un hijo desplazable: los estados vacíos van en una LazyColumn.
                is DealsUiState.Error, is DealsUiState.Success -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refreshDeals,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        state is DealsUiState.Error -> ScrollableCentered {
                            ErrorContent(message = state.message, onRetry = viewModel::refreshDeals)
                        }
                        state is DealsUiState.Success && state.deals.isNotEmpty() ->
                            DealsGrid(deals = state.deals, bottomInset = bottomInset)
                        state is DealsUiState.Success && state.isFiltered -> ScrollableCentered {
                            NoMatchesContent(onClearFilters = viewModel::clearFilters)
                        }
                        else -> ScrollableCentered {
                            Text("No hay ofertas por ahora.\nVuelve mañana 🎮", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("Buscar juegos…") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Borrar búsqueda")
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        // Píldora sin borde sobre surfaceVariant; al no haber borde, la lupa cambia a primary para indicar el foco.
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun GenreChips(genres: List<String>, selectedGenre: String?, onGenreClick: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(genres, key = { it }) { genre ->
            val selected = genre == selectedGenre
            FilterChip(
                selected = selected,
                onClick = { onGenreClick(genre) },
                label = { Text(genre) },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun DealsGrid(deals: List<Deal>, bottomInset: Dp) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = bottomInset + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(deals, key = { it.id }) { deal ->
            DealCard(deal = deal, onClick = { openProduct(context, deal.productUrl) })
        }
    }
}

@Composable
fun DealCard(deal: Deal, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        // Cristal: translúcida para que el degradado se asome; sin elevación, así la sombra no se ve a través.
        // contentColor explícito por la misma razón que en el Scaffold: un color con alpha no tiene pareja en el tema.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Box {
            AsyncImage(
                model = deal.imageUrl?.let(::resized),
                contentDescription = deal.title,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
            DiscountBadge(
                percent = deal.discountPercent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = deal.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                minLines = 2, // misma altura en todas las tarjetas de una fila
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Sin géneros se deja una línea en blanco para no descuadrar la altura de la fila.
                text = deal.genres.joinToString(", ").ifEmpty { " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = deal.originalPriceText,
                style = MaterialTheme.typography.bodySmall,
                // onSurface (no White) para que también funcione en modo claro; alpha 0.7 lo hace secundario
                // y mantiene AA (con 0.6 bajaba a 4.3:1 en modo claro).
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textDecoration = TextDecoration.LineThrough,
            )
            Text(
                text = deal.discountedPriceText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DiscountBadge(percent: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(DiscountBadgeColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (percent >= SUPER_DEAL_THRESHOLD) PulsingFire()
        Text(
            text = "-$percent%",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Fuego con efecto de "latido" para las super ofertas. */
@Composable
private fun PulsingFire() {
    val transition = rememberInfiniteTransition(label = "fire")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fireScale",
    )
    Text(
        text = "🔥",
        style = MaterialTheme.typography.labelLarge,
        // graphicsLayer lee `scale` solo en la fase de dibujo: anima sin recomponer la tarjeta en cada frame.
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text("No se pudieron cargar las ofertas", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}

@Composable
private fun NoMatchesContent(onClearFilters: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text("🔍", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "No se encontraron ofertas para esta búsqueda",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClearFilters) { Text("Limpiar filtros") }
    }
}

/** Centrado y desplazable (requisito de PullToRefreshBox para detectar el gesto). */
@Composable
private fun ScrollableCentered(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** El CDN de la PS Store redimensiona con `?w=`: ~15 KB en vez de ~800 KB por portada. */
private fun resized(url: String): String =
    if (url.contains("image.api.playstation.com") && !url.contains('?')) "$url?w=440" else url

private fun openProduct(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Log.w("DealsScreen", "No hay app para abrir $url", e)
    }
}

@Preview(showBackground = true, widthDp = 200)
@Composable
private fun DealCardPreview() {
    PSDealsTheme {
        DealCard(
            deal = Deal(
                id = "preview",
                title = "Marvel's Spider-Man: Game of the Year Edition",
                originalPriceText = "US$39.99",
                discountedPriceText = "US$9.99",
                discountPercent = 75,
                imageUrl = null,
                productUrl = "https://store.playstation.com",
                lastSeenAt = null,
                genres = listOf("Acción", "Aventura"),
            ),
            onClick = {},
        )
    }
}
