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
internal fun UserAvatar(user: SessionUser, size: Dp, modifier: Modifier = Modifier) {
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
