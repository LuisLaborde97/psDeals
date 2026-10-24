package co.golab.deals.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.golab.deals.BuildConfig
import co.golab.deals.R
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
private val CardShape = RoundedCornerShape(20.dp)
private val ButtonShape = RoundedCornerShape(16.dp)

/**
 * Cuenta vinculada con Google: perfil, resumen de lo guardado y acciones. Panel deslizable como el detalle de
 * ofertas, con la acción destructiva aislada al final en su propia tarjeta.
 *
 * @param onOpenWishlist la tarjeta de la lista de deseos lleva a Favoritos.
 * @param onDeleteAccount se llama tras confirmar; debe volver a pedir el token de Google (ver
 *   [rememberGoogleIdTokenRequest]). Al terminar la sesión pasa a anónima y quien lo muestra lo cierra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    account: AccountUiState,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenWishlist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val user = account.user ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember { mutableStateOf(false) }
    // Cierra con la animación del panel y después avisa (si no, el panel desaparecería de golpe).
    val closeThen = { action: () -> Unit ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            action()
            onDismiss()
        }
    }

    if (confirmingDelete) {
        DeleteAccountDialog(
            onConfirm = {
                confirmingDelete = false
                onDeleteAccount()
            },
            onDismiss = { confirmingDelete = false },
        )
    }

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
            // Perfil: avatar con anillo del color de acento, nombre y correo.
            UserAvatar(
                user = user,
                size = 88.dp,
                modifier = Modifier
                    .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
                    .padding(5.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                user.displayName ?: "Tu cuenta",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            user.email?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            SyncedBadge()

            Spacer(Modifier.height(24.dp))
            // IntrinsicSize.Min + fillMaxHeight: ambas tarjetas igual de altas aunque solo una tenga "Ver lista".
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                StatCard(
                    iconRes = R.drawable.ic_favorite,
                    iconTint = MaterialTheme.colorScheme.error,
                    value = account.wishlist.size,
                    label = if (account.wishlist.size == 1) "Juego guardado" else "Juegos guardados",
                    action = "Ver lista",
                    onClick = { closeThen(onOpenWishlist) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StatCard(
                    iconRes = R.drawable.ic_history,
                    iconTint = MaterialTheme.colorScheme.primary,
                    value = account.searchHistory.size,
                    label = if (account.searchHistory.size == 1) "Búsqueda reciente" else "Búsquedas recientes",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            Spacer(Modifier.height(12.dp))
            InfoRow(
                "Tu lista de deseos y tu historial se guardan en tu cuenta de Google y te acompañan si cambias de teléfono.",
            )

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { closeThen(onSignOut) },
                enabled = !account.isDeletingAccount,
                shape = ButtonShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Cerrar sesión", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(Modifier.height(24.dp))
            DangerZone(isDeleting = account.isDeletingAccount, onDelete = { confirmingDelete = true })

            Spacer(Modifier.height(20.dp))
            Text(
                "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** "Sincronizada con Google": deja claro que la lista no vive solo en este teléfono. */
@Composable
private fun SyncedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Sincronizada con Google",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Tarjeta con un número grande; con [onClick] es tocable y muestra [action] como pista. */
@Composable
private fun StatCard(
    iconRes: Int,
    iconTint: Color,
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(iconTint.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(painterResource(iconRes), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("$value", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    // Card(onClick) solo si es tocable: así la otra tarjeta no anuncia un botón que no hace nada.
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = CardShape, colors = colors) { content() }
    } else {
        Card(modifier = modifier, shape = CardShape, colors = colors) { content() }
    }
}

@Composable
private fun InfoRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Eliminar cuenta, aislado al final con borde de alerta (requisito de Google Play: debe poder hacerse en la app). */
@Composable
private fun DangerZone(isDeleting: Boolean, onDelete: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, error.copy(alpha = 0.35f), CardShape)
            .padding(16.dp),
    ) {
        Text("Eliminar cuenta", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = error)
        Spacer(Modifier.height(4.dp))
        Text(
            "Borra tu lista de deseos, tu historial de búsqueda y los datos de tu cuenta. No se puede deshacer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onDelete,
            enabled = !isDeleting,
            shape = ButtonShape,
            border = BorderStroke(1.dp, error.copy(alpha = if (isDeleting) 0.3f else 0.7f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isDeleting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = error)
                Spacer(Modifier.width(8.dp))
                Text("Eliminando cuenta…")
            } else {
                Text("Eliminar mi cuenta")
            }
        }
    }
}

/** Confirmación antes de borrar: la acción no se puede deshacer. */
@Composable
private fun DeleteAccountDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Eliminar tu cuenta?") },
        text = {
            Text(
                "Se borrarán tu lista de deseos, tu historial de búsqueda y los datos de tu cuenta de Google en " +
                    "GoLab Deals. Esta acción no se puede deshacer.\n\nPara confirmar, elige tu cuenta de Google.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Eliminar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
