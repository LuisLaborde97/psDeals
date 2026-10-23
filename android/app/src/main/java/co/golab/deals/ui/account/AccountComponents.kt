package co.golab.deals.ui.account

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import co.golab.deals.R
import co.golab.deals.data.GoogleIdTokenProvider
import co.golab.deals.data.NoGoogleAccountException
import co.golab.deals.data.SessionUser
import co.golab.deals.notifications.BatteryOptimizationHelper
import kotlinx.coroutines.launch

private const val TAG = "AccountComponents"

/**
 * Acción del botón de cuenta: abre la hoja de Google si la sesión es anónima, o el resumen si ya
 * está vinculada. El token se pide aquí y no en el ViewModel porque Credential Manager necesita la
 * Activity, y el ViewModel no debe retenerla.
 */
@Composable
fun rememberGoogleSignIn(
    isSignedInWithGoogle: Boolean,
    onIdToken: (String) -> Unit,
    onError: (String) -> Unit,
    onShowAccount: () -> Unit,
): () -> Unit {
    val requestIdToken = rememberGoogleIdTokenRequest(onIdToken = onIdToken, onError = onError)
    val signedIn by rememberUpdatedState(isSignedInWithGoogle)
    val showAccount by rememberUpdatedState(onShowAccount)
    return remember(requestIdToken) { { if (signedIn) showAccount() else requestIdToken() } }
}

/**
 * Abre la hoja "Continuar con Google" y entrega el ID token. La usan el inicio de sesión y la eliminación
 * de cuenta, que vuelve a pedirlo para confirmar la identidad antes de borrar.
 */
@Composable
fun rememberGoogleIdTokenRequest(onIdToken: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenProvider = remember { GoogleIdTokenProvider() }
    // Generado por el plugin de google-services a partir del cliente OAuth "Web" de google-services.json.
    val webClientId = stringResource(R.string.default_web_client_id)
    val currentOnIdToken by rememberUpdatedState(onIdToken)
    val currentOnError by rememberUpdatedState(onError)
    return remember(context, scope, webClientId) {
        {
            scope.launch {
                try {
                    currentOnIdToken(tokenProvider.requestIdToken(context, webClientId))
                } catch (e: GetCredentialCancellationException) {
                    // El usuario cerró la hoja: no hay nada que avisar.
                } catch (e: NoGoogleAccountException) {
                    currentOnError("Agrega una cuenta de Google en los ajustes del teléfono")
                } catch (e: GetCredentialException) {
                    Log.w(TAG, "Credential Manager falló", e)
                    currentOnError("No se pudo abrir el inicio de sesión de Google")
                }
            }
        }
    }
}

/**
 * Anónimo = contorno gris; sesión de Google = avatar (disco relleno en primary, con foto encima si
 * la hay). El cambio de forma y relleno se distingue de un vistazo; solo cambiar el tono no bastaba:
 * con los colores dinámicos en modo oscuro, primary y el gris del contorno son ambos claros.
 */
@Composable
fun AccountButton(account: AccountUiState, onClick: () -> Unit) {
    val user = account.user
    IconButton(onClick = onClick, enabled = !account.isSigningIn) {
        when {
            account.isSigningIn -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            user != null && account.isSignedInWithGoogle ->
                UserAvatar(user = user, size = 30.dp, modifier = Modifier.semantics { contentDescription = "Tu cuenta" })
            else -> Icon(painterResource(R.drawable.ic_account_circle), contentDescription = "Iniciar sesión con Google")
        }
    }
}

/**
 * Disco en primary con la inicial (o el ícono de cuenta relleno) y, encima, la foto de Google. Si la
 * foto tarda o falla, se ve el disco en vez de un círculo vacío. La caché de Coil no da fotos viejas:
 * cuando la foto de Google cambia, cambia la URL (que es la clave de la caché).
 */
@Composable
private fun UserAvatar(user: SessionUser, size: Dp, modifier: Modifier = Modifier) {
    val initial = user.displayName?.trim()?.firstOrNull()?.uppercase()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    ) {
        if (initial != null) {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.45f).sp,
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_account_circle_filled),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.8f),
            )
        }
        if (user.photoUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(user.photoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Resumen de la cuenta vinculada; mismo estilo que el diálogo de monedas.
 * @param onDeleteAccount se llama tras confirmar; debe volver a pedir el token de Google (ver
 *   [rememberGoogleIdTokenRequest]). Al terminar la sesión pasa a anónima y quien lo muestra lo cierra.
 * @param onBatterySettings vuelve a abrir el aviso de batería (que lleva a Ajustes).
 */
@Composable
fun AccountDialog(
    account: AccountUiState,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val user = account.user ?: return
    val count = account.wishlist.size
    var confirmingDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Se lee al abrir el diálogo: si el usuario vuelve de Ajustes, el diálogo ya se cerró y al reabrirlo se relee.
    val batteryUnrestricted = remember { BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }
    if (confirmingDelete) {
        DeleteAccountDialog(
            onConfirm = {
                confirmingDelete = false
                onDeleteAccount()
            },
            onDismiss = { confirmingDelete = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { UserAvatar(user = user, size = 56.dp) },
        title = { Text(user.displayName ?: "Tu cuenta", textAlign = TextAlign.Center) },
        text = {
            Column {
                user.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (count == 1) "1 juego en tu lista de deseos" else "$count juegos en tu lista de deseos",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tu lista se guarda en tu cuenta de Google y te acompaña si cambias de teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBatterySettings, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icon(painterResource(R.drawable.ic_notification), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (batteryUnrestricted) "Alertas en segundo plano: sin restricciones" else "Recibir alertas a tiempo (batería)")
                }
                // Requisito de Google Play: la cuenta debe poder eliminarse desde la propia app.
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !account.isDeletingAccount,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    if (account.isDeletingAccount) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Eliminando cuenta…")
                    } else {
                        Text("Eliminar cuenta")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            TextButton(
                onClick = {
                    onSignOut()
                    onDismiss()
                },
            ) { Text("Cerrar sesión") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
