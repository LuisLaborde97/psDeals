package com.example.psdeals.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.psdeals.R
import com.example.psdeals.ui.account.AccountViewModel
import com.example.psdeals.ui.deals.DealsScreen
import com.example.psdeals.ui.deals.rememberBackgroundBrush
import com.example.psdeals.ui.update.UpdateDialog
import com.example.psdeals.ui.update.UpdateViewModel
import com.example.psdeals.ui.wishlist.WishlistScreen

/** Rutas como texto: con dos pantallas sin argumentos no compensa el plugin de serialización de las rutas tipadas. */
private object Routes {
    const val DEALS = "deals"
    const val WISHLIST = "wishlist"
}

private enum class Tab(val route: String, val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    DEALS(Routes.DEALS, "Ofertas", R.drawable.ic_local_offer, R.drawable.ic_local_offer),
    WISHLIST(Routes.WISHLIST, "Favoritos", R.drawable.ic_favorite_border, R.drawable.ic_favorite),
}

/**
 * @param openWishlistRequest se tocó una alerta de wishlist: se cambia a Favoritos y se avisa con
 *   [onOpenWishlistHandled] para que el pedido no se repita en la siguiente recomposición.
 * @param onDealsReady las ofertas terminaron de cargar (ver MainActivity: retiene la splash hasta entonces).
 */
@Composable
fun AppNavigation(
    openWishlistRequest: Boolean = false,
    onOpenWishlistHandled: () -> Unit = {},
    onDealsReady: () -> Unit = {},
) {
    val navController = rememberNavController()
    LaunchedEffect(openWishlistRequest) {
        if (openWishlistRequest) {
            navController.navigateToTab(Routes.WISHLIST)
            onOpenWishlistHandled()
        }
    }
    // Un solo AccountViewModel para toda la app (alcance de la Activity): la insignia, el login y los
    // corazones de ambas pantallas leen el mismo estado.
    val accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.Factory)
    val account by accountViewModel.uiState.collectAsStateWithLifecycle()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Aquí y no en una pantalla: el aviso de versión nueva debe salir sobre cualquier pestaña.
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory)
    val pendingUpdate by updateViewModel.pendingUpdate.collectAsStateWithLifecycle()
    pendingUpdate?.let { update -> UpdateDialog(update = update, onPostpone = { updateViewModel.postpone(update) }) }

    // El degradado va aquí, detrás de las pantallas y de la barra inferior, para que sea continuo.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(rememberBackgroundBrush()),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Sin insets propios: cada pantalla maneja la barra de estado con su TopAppBar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f)) {
                Tab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(tab.route) },
                        label = { Text(tab.label) },
                        icon = {
                            val count = account.wishlist.size
                            BadgedBox(badge = { if (tab == Tab.WISHLIST && count > 0) Badge { Text("$count") } }) {
                                Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DEALS,
            // consumeWindowInsets: la barra inferior ya cubre la de navegación del sistema; sin esto, las
            // pantallas volverían a sumar ese espacio debajo de la grilla.
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Routes.DEALS) { DealsScreen(accountViewModel = accountViewModel, onContentReady = onDealsReady) }
            composable(Routes.WISHLIST) {
                WishlistScreen(
                    accountViewModel = accountViewModel,
                    onBrowseDeals = { navController.navigateToTab(Routes.DEALS) },
                )
            }
        }
    }
}

/**
 * Navegación de pestañas: una sola copia de cada pantalla en la pila, y cada una conserva su estado
 * (búsqueda, filtros, scroll) al ir y volver.
 */
private fun NavController.navigateToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
