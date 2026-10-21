package com.example.psdeals.ui.deals

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.psdeals.R
import com.example.psdeals.model.Deal
import com.example.psdeals.model.StorePlatform
import com.example.psdeals.ui.openStoreUrl
import com.example.psdeals.ui.account.AccountButton
import com.example.psdeals.ui.account.AccountDialog
import com.example.psdeals.ui.account.AccountViewModel
import com.example.psdeals.ui.account.rememberGoogleSignIn
import com.example.psdeals.ui.theme.PSDealsTheme
import kotlinx.coroutines.delay

/** Rojo sobrio, llamativo sin ser chillón. Blanco encima = 4.97:1 (cumple WCAG AA para texto normal). */
private val DiscountBadgeColor = Color(0xFFD62839)
private const val SUPER_DEAL_THRESHOLD = 70
private const val EXPIRED_ALPHA = 0.55f
private const val DEAL_CARD_CONTENT_TYPE = "deal_card"
/** Mínimo para guardar una búsqueda: con 1-2 letras casi siempre es una búsqueda a medio escribir. */
private const val MIN_SEARCH_LENGTH = 3
private const val SEARCH_RECORD_DELAY_MS = 1_500L
/** Rosa coral: distinto del rojo del descuento para que el corazón no se confunda con la insignia. */
private val WishlistColor = Color(0xFFFF5C7A)

// Modo oscuro: grafito con un leve tinte azul arriba → negro casi puro abajo. Neutro para no competir con las portadas.
private val DarkGradient = listOf(Color(0xFF1C1C24), Color(0xFF121216), Color(0xFF09090B))
// Versión clara (azul hielo → melocotón) para que el texto oscuro del tema claro siga siendo legible.
private val LightGradient = listOf(Color(0xFFE8F0FF), Color(0xFFF4F1F8), Color(0xFFFFECE6))

/** Elige el degradado según el tema activo (incluye colores dinámicos), no solo según el sistema. */
@Composable
fun rememberBackgroundBrush(): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(isDark) { Brush.verticalGradient(if (isDark) DarkGradient else LightGradient) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(
    viewModel: DealsViewModel = viewModel(factory = DealsViewModel.Factory),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory),
    onContentReady: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Error también cuenta: retener la splash no lo arreglaría, y la pantalla de error ofrece reintentar.
    val isReady = uiState !is DealsUiState.Loading
    LaunchedEffect(isReady) { if (isReady) onContentReady() }
    val genres by viewModel.availableGenres.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val selectedPlatform by viewModel.selectedPlatform.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val account by accountViewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCurrencyInfo by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }

    LaunchedEffect(accountViewModel) {
        accountViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    val onAccountClick = rememberGoogleSignIn(
        isSignedInWithGoogle = account.isSignedInWithGoogle,
        onIdToken = accountViewModel::signInWithGoogle,
        onError = accountViewModel::showMessage,
        onShowAccount = { showAccount = true },
    )

    if (showCurrencyInfo) CurrencyInfoDialog(onDismiss = { showCurrencyInfo = false })
    if (showAccount && account.isSignedInWithGoogle) {
        AccountDialog(account = account, onSignOut = accountViewModel::signOut, onDismiss = { showAccount = false })
    }

    // Búsqueda reciente: se guarda cuando el usuario deja de escribir 1,5 s (o pulsa "Buscar"), no en
    // cada tecla, para no escribir en Firestore por cada letra. rememberSaveable evita volver a guardar
    // el mismo término al regresar de otra pestaña.
    var lastRecordedSearch by rememberSaveable { mutableStateOf("") }
    val recordSearch = { query: String ->
        val term = query.trim()
        if (term.length >= MIN_SEARCH_LENGTH && !term.equals(lastRecordedSearch, ignoreCase = true)) {
            lastRecordedSearch = term
            accountViewModel.recordSearch(term)
        }
    }
    LaunchedEffect(viewModel.searchQuery) {
        delay(SEARCH_RECORD_DELAY_MS)
        recordSearch(viewModel.searchQuery)
    }

    // El degradado lo pinta AppNavigation detrás de todo (también de la barra inferior);
    // Scaffold y TopAppBar son transparentes para dejarlo ver.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        // Obligatorio con fondo transparente: contentColorFor(Transparent) no encuentra pareja en el tema y
        // cae al LocalContentColor por defecto (negro), dejando el texto heredado ilegible en modo oscuro.
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                actions = {
                    AccountButton(account = account, onClick = onAccountClick)
                    IconButton(onClick = { showCurrencyInfo = true }) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = "Sobre los precios y monedas")
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
            SearchField(
                query = viewModel.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch = recordSearch,
                history = account.searchHistory,
                onHistoryClick = { term ->
                    viewModel.onSearchQueryChange(term)
                    // Vuelve a guardarla para subirla al primer puesto del historial.
                    lastRecordedSearch = ""
                    recordSearch(term)
                },
                onRemoveHistory = accountViewModel::removeSearch,
            )
            PlatformChips(
                selectedPlatform = selectedPlatform,
                onPlatformClick = { platform ->
                    // Barra expandida al cambiar de tienda: el contador de la tienda nueva queda a la vista.
                    scrollBehavior.state.heightOffset = 0f
                    viewModel.onPlatformClick(platform)
                },
            )
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
                            // Grilla nueva (y su scroll en 0) cuando llega la lista de otra tienda, o al empezar o
                            // vaciar una búsqueda. Reiniciar el LazyGridState al tocar no basta: la lista llega
                            // después y la grilla conservaría a la vista el primer elemento anterior si existe en
                            // la lista nueva. Mientras se escribe (texto → texto) la clave no cambia: la grilla no
                            // se recrea en cada tecla.
                            key(state.platform, state.isSearching) {
                                DealsGrid(
                                    deals = state.deals,
                                    wishlist = account.wishlist,
                                    onToggleWishlist = accountViewModel::toggleWishlist,
                                    bottomInset = bottomInset,
                                )
                            }
                        state is DealsUiState.Success && state.isFiltered -> ScrollableCentered {
                            NoMatchesContent(onClearFilters = viewModel::clearFilters)
                        }
                        else -> ScrollableCentered {
                            val store = selectedPlatform?.let { " de ${it.label}" }.orEmpty()
                            Text("No hay ofertas$store por ahora.\nVuelve mañana 🎮", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    // Solo con el campo vacío: en cuanto escribe, la lista filtrada ya es la respuesta.
    val showHistory = isFocused && query.isEmpty() && history.isNotEmpty()
    // "Atrás" cierra primero el panel en vez de salir de la app.
    BackHandler(enabled = showHistory) { focusManager.clearFocus() }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onFocusChanged { isFocused = it.isFocused },
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
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                onSearch(query)
            },
        ),
    )
    AnimatedVisibility(visible = showHistory, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        SearchHistoryPanel(
            history = history,
            onClick = { term ->
                onHistoryClick(term)
                focusManager.clearFocus() // cierra panel y teclado: el usuario ya eligió
            },
            onRemove = onRemoveHistory,
        )
    }
}

/**
 * Debajo del buscador, en el flujo de la pantalla (empuja los chips), y no como popup flotante:
 * con el teclado abierto un popup se solaparía con él. Opaco para leerse sobre la grilla.
 */
@Composable
private fun SearchHistoryPanel(history: List<String>, onClick: (String) -> Unit, onRemove: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            "Búsquedas recientes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        // Tope de alto: con 20 términos el panel taparía media pantalla.
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
        ) {
            history.forEach { term ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Buscar $term") { onClick(term) }
                        .padding(start = 16.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_history),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        term,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(term) }) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = "Borrar \"$term\" del historial",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** `null` representa "Todas": no es una tienda, así que no vive en [StorePlatform]. */
@Composable
private fun PlatformChips(selectedPlatform: StorePlatform?, onPlatformClick: (StorePlatform?) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            SelectableChip(label = "Todas", selected = selectedPlatform == null, onClick = { onPlatformClick(null) })
        }
        items(StorePlatform.entries, key = { it.id }) { platform ->
            SelectableChip(
                label = platform.label,
                selected = platform == selectedPlatform,
                onClick = { onPlatformClick(platform) },
                brandColor = platform.brandColor,
                icon = platform.iconRes,
            )
        }
    }
}

@Composable
private fun GenreChips(genres: List<String>, selectedGenre: String?, onGenreClick: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(genres, key = { it }) { genre ->
            SelectableChip(label = genre, selected = genre == selectedGenre, onClick = { onGenreClick(genre) })
        }
    }
}

/**
 * @param brandColor relleno al estar seleccionado (texto blanco); null = colores del tema ("Todas" y géneros).
 * @param icon logo fijo en lugar del check: el relleno de marca ya indica la selección.
 */
@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    brandColor: Color? = null,
    @DrawableRes icon: Int? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = if (brandColor == null) {
            FilterChipDefaults.filterChipColors()
        } else {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = brandColor,
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
            )
        },
        // Los rellenos de marca son oscuros (Epic queda en 1.4:1 contra el grafito): el borde translúcido
        // conserva la silueta del chip seleccionado sobre el fondo.
        border = if (brandColor == null) {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = selected)
        } else {
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                selectedBorderColor = Color.White.copy(alpha = 0.35f),
                selectedBorderWidth = 1.dp,
            )
        },
        // Sin tint explícito: FilterChip aplica leadingIconColor (gris neutro) o selectedLeadingIconColor (blanco).
        leadingIcon = when {
            icon != null -> {
                { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            }
            selected -> {
                { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            }
            else -> null
        },
    )
}

/** Compartida con WishlistScreen. @param isExpired marca ofertas terminadas (solo en la lista de deseos). */
@Composable
fun DealsGrid(
    deals: List<Deal>,
    wishlist: Set<String>,
    onToggleWishlist: (String) -> Unit,
    bottomInset: Dp,
    isExpired: (Deal) -> Boolean = { false },
) {
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
        // contentType: todas las celdas son del mismo tipo, así Compose reutiliza las jerarquías de las tarjetas
        // que salen de pantalla en vez de crearlas desde cero en cada lanzamiento rápido.
        items(deals, key = { it.id }, contentType = { DEAL_CARD_CONTENT_TYPE }) { deal ->
            val expired = isExpired(deal)
            DealCard(
                deal = deal,
                onClick = { context.openStoreUrl(deal.storeUrl ?: deal.productUrl) },
                isWishlisted = deal.id in wishlist,
                onToggleWishlist = { onToggleWishlist(deal.id) },
                onShare = { shareDeal(context, deal, expired) },
                isExpired = expired,
            )
        }
    }
}

/**
 * @param onToggleWishlist null oculta el corazón (ej. en la vista previa).
 * @param isExpired la rebaja terminó (el scraper dejó de verla): tarjeta atenuada y precios como referencia.
 */
@Composable
fun DealCard(
    deal: Deal,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWishlisted: Boolean = false,
    onToggleWishlist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    isExpired: Boolean = false,
) {
    // Card(onClick) y no Modifier.clickable: así el ripple se recorta a las esquinas redondeadas (antes se
    // dibujaba rectangular por fuera). El corazón y compartir son botones propios: consumen su toque, y
    // su ripple circular no dispara el de la tarjeta.
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Atenúa la tarjeta entera, botones incluidos (siguen siendo tocables: alpha no afecta a los toques).
            // Solo en las terminadas: antes cada tarjeta llevaba su capa gráfica aunque su alpha fuera 1.
            .then(if (isExpired) Modifier.alpha(EXPIRED_ALPHA) else Modifier),
        shape = RoundedCornerShape(16.dp),
        // Cristal: translúcida para que el degradado se asome; sin elevación, así la sombra no se ve a través.
        // contentColor explícito por la misma razón que en el Scaffold: un color con alpha no tiene pareja en el tema.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // Borde de cristal: sobre el fondo grafito la tarjeta translúcida apenas contrasta (~1.01:1).
        // Alpha 0.5 deja el trazo en ~1.3:1: visible pero sutil (con 0.2 quedaba en 1.1:1, casi invisible).
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
            val badgeModifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
            if (isExpired) ExpiredBadge(badgeModifier) else DiscountBadge(percent = deal.discountPercent, modifier = badgeModifier)
            if (onToggleWishlist != null) {
                WishlistButton(
                    isWishlisted = isWishlisted,
                    onToggle = onToggleWishlist,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Sobre el cristal (no sobre la portada) para que el tint sutil tenga contraste estable.
                StorePlatform.fromId(deal.platform)?.let { platform ->
                    Icon(
                        painter = painterResource(platform.iconRes),
                        contentDescription = platform.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    // Sin géneros se deja una línea en blanco para no descuadrar la altura de la fila.
                    text = deal.genres.joinToString(", ").ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
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
                // En la zona de texto y no sobre la portada: cada esquina de la imagen tiene una sola acción.
                if (onShare != null) {
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_share),
                            contentDescription = "Compartir oferta",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
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

/**
 * Color de marca para el chip seleccionado. Todos dan ≥ 7:1 (WCAG AAA) con texto blanco; por eso
 * PlayStation y Xbox usan tonos más oscuros que sus colores insignia (#0070D1 = 4.95:1, #107C10 = 5.37:1).
 */
private val StorePlatform.brandColor: Color
    get() = when (this) {
        StorePlatform.PLAYSTATION -> Color(0xFF00439C) // 9.20:1
        StorePlatform.STEAM -> Color(0xFF2A475E) // 9.72:1
        StorePlatform.EPIC -> Color(0xFF313131) // 13.01:1
        StorePlatform.XBOX -> Color(0xFF0E5F0E) // 7.89:1
    }

/** Logo de la tienda (res/drawable). Vive en la UI para que el modelo no dependa de recursos de Android. */
@get:DrawableRes
private val StorePlatform.iconRes: Int
    get() = when (this) {
        StorePlatform.PLAYSTATION -> R.drawable.ic_playstation
        StorePlatform.STEAM -> R.drawable.ic_steam
        StorePlatform.EPIC -> R.drawable.ic_epic
        StorePlatform.XBOX -> R.drawable.ic_xbox
    }

/**
 * Corazón sobre la portada, con un círculo oscuro detrás para que se lea sobre cualquier imagen.
 * IconToggleButton (y no IconButton) para que TalkBack anuncie el estado: "Lista de deseos, activado".
 */
@Composable
private fun WishlistButton(isWishlisted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconToggleButton(checked = isWishlisted, onCheckedChange = { onToggle() }, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(
                painter = painterResource(if (isWishlisted) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                contentDescription = "Lista de deseos",
                tint = if (isWishlisted) WishlistColor else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Sustituye al % de descuento cuando la rebaja ya terminó. */
@Composable
private fun ExpiredBadge(modifier: Modifier = Modifier) {
    Text(
        text = "Oferta terminada",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
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

/**
 * Explica por qué conviven US$ y COP en la lista. Opaco (no cristal como las tarjetas): sobre la
 * grilla de portadas un diálogo translúcido sería ilegible.
 */
@Composable
private fun CurrencyInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
        title = { Text("Sobre los precios y monedas", textAlign = TextAlign.Center) },
        text = {
            Text(
                // Epic: las rebajas vienen de IsThereAnyDeal, que para Colombia solo da el precio de EE. UU. en USD.
                "Cada tienda publica sus precios para Colombia en una moneda distinta, y los mostramos tal cual:\n\n" +
                    "• PlayStation cobra en dólares (US$).\n" +
                    "• Steam y Xbox cobran en pesos colombianos (COP).\n" +
                    "• Epic Games: los juegos gratis se muestran en pesos. En las rebajas mostramos como referencia " +
                    "el precio en dólares de la tienda de EE. UU.; lo que Epic te cobre en pesos puede ser distinto.\n\n" +
                    "PSDeals es un agregador gratuito y no aplica ninguna comisión. Si pagas en dólares, el cargo " +
                    "final dependerá de la tasa de cambio de tu banco.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Entendido") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Pide las portadas ya reducidas a la CDN de cada tienda. Coil decodifica al tamaño de la tarjeta, pero
 * antes tiene que descargar y decodificar el archivo entero: con una portada de 3 MB eso cuesta CPU y
 * memoria justo durante el primer scroll. Xbox ya llega con `?w=440` desde el backend; Steam e ITAD
 * sirven portadas de ~50 KB.
 */
private fun resized(url: String): String = when {
    url.contains('?') && !url.contains("cdn1.epicgames.com") -> url
    // `?w=`: ~15-60 KB en vez de ~800 KB.
    url.contains("image.api.playstation.com") -> "$url?w=440"
    // Portadas oficiales de Epic: 0,4-3,3 MB originales → 24-59 KB. resize=1 conserva la proporción.
    url.contains("cdn1.epicgames.com") -> url + (if (url.contains('?')) "&" else "?") + "resize=1&w=480&h=720"
    else -> url
}

/** Hoja de compartir del sistema con un texto listo para WhatsApp, Telegram, etc. */
private fun shareDeal(context: Context, deal: Deal, isExpired: Boolean) {
    val store = StorePlatform.fromId(deal.platform)?.label ?: deal.platform
    val url = deal.storeUrl ?: deal.productUrl
    val text = when {
        // Ya no está rebajado: el precio guardado sería engañoso.
        isExpired -> "Mira ${deal.title} en $store: $url"
        // 100 %: "Gratis" (Epic) o "US$0.00" (PlayStation); "a US$0.00" se leería raro.
        deal.discountPercent >= 100 -> "¡Mira esta oferta! ${deal.title} está gratis en $store: $url"
        else -> "¡Mira esta oferta! ${deal.title} a ${deal.discountedPriceText} (-${deal.discountPercent}%) en $store: $url"
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, deal.title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Compartir oferta"))
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
                platform = StorePlatform.PLAYSTATION.id,
                storeUrl = "https://store.playstation.com",
            ),
            onClick = {},
        )
    }
}
