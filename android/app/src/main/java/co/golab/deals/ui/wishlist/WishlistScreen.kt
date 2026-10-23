package co.golab.deals.ui.wishlist

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.golab.deals.R
import co.golab.deals.ui.account.AccountViewModel
import co.golab.deals.ui.account.rememberGoogleSignIn
import co.golab.deals.ui.deals.DealsGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    accountViewModel: AccountViewModel,
    onBrowseDeals: () -> Unit,
    viewModel: WishlistViewModel = viewModel(factory = WishlistViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val account by accountViewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(accountViewModel) {
        accountViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    val signIn = rememberGoogleSignIn(
        isSignedInWithGoogle = account.isSignedInWithGoogle,
        onIdToken = accountViewModel::signInWithGoogle,
        onError = accountViewModel::showMessage,
        onShowAccount = {},
    )

    // Transparente como DealsScreen: el degradado lo pinta AppNavigation.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Column {
                        Text("Lista de deseos", fontWeight = FontWeight.Bold)
                        (uiState as? WishlistUiState.Success)?.takeIf { it.deals.isNotEmpty() }?.let {
                            val active = it.deals.count { deal -> deal.id !in it.expiredIds }
                            val saved = if (it.deals.size == 1) "1 guardado" else "${it.deals.size} guardados"
                            Text(
                                "$saved · $active en oferta",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            when (val state = uiState) {
                WishlistUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is WishlistUiState.Error -> CenteredMessage(
                    title = "No se pudo cargar tu lista",
                    body = state.message,
                )
                is WishlistUiState.Success -> if (state.deals.isEmpty()) {
                    EmptyWishlist(onBrowseDeals = onBrowseDeals)
                } else {
                    if (!account.isSignedInWithGoogle) SaveWithGoogleBanner(onSignIn = signIn)
                    DealsGrid(
                        deals = state.deals,
                        wishlist = account.wishlist,
                        onToggleWishlist = accountViewModel::toggleWishlist,
                        bottomInset = innerPadding.calculateBottomPadding(),
                        isExpired = { deal -> deal.id in state.expiredIds },
                    )
                }
            }
        }
    }
}

/** Con sesión anónima la lista vive solo en este teléfono: perderla al reinstalar sería frustrante. */
@Composable
private fun SaveWithGoogleBanner(onSignIn: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        // Mismo cristal que las tarjetas de ofertas.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_account_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Inicia sesión para no perder tu lista si cambias de teléfono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignIn) { Text("Iniciar sesión") }
        }
    }
}

@Composable
private fun EmptyWishlist(onBrowseDeals: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_favorite_border),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Tu lista de deseos está vacía", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Toca el corazón de cualquier oferta para guardarla aquí. Si la rebaja termina, el juego se queda en tu lista.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBrowseDeals) { Text("Explorar ofertas") }
    }
}

@Composable
private fun CenteredMessage(title: String, body: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}
