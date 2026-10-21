package com.example.psdeals.ui.account

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import coil3.compose.AsyncImage
import com.example.psdeals.R
import com.example.psdeals.data.GoogleIdTokenProvider
import com.example.psdeals.data.NoGoogleAccountException
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenProvider = remember { GoogleIdTokenProvider() }
    // Generado por el plugin de google-services a partir del cliente OAuth "Web" de google-services.json.
    val webClientId = stringResource(R.string.default_web_client_id)
    val signedIn by rememberUpdatedState(isSignedInWithGoogle)
    return remember(context, scope, webClientId) {
        {
            if (signedIn) {
                onShowAccount()
            } else {
                scope.launch {
                    try {
                        onIdToken(tokenProvider.requestIdToken(context, webClientId))
                    } catch (e: GetCredentialCancellationException) {
                        // El usuario cerró la hoja: no hay nada que avisar.
                    } catch (e: NoGoogleAccountException) {
                        onError("Agrega una cuenta de Google en los ajustes del teléfono")
                    } catch (e: GetCredentialException) {
                        Log.w(TAG, "Credential Manager falló", e)
                        onError("No se pudo abrir el inicio de sesión de Google")
                    }
                }
            }
        }
    }
}

/** Contorno = sesión anónima; foto (o ícono relleno en color primario) = cuenta de Google vinculada. */
@Composable
fun AccountButton(account: AccountUiState, onClick: () -> Unit) {
    val photoUrl = account.user?.photoUrl
    IconButton(onClick = onClick, enabled = !account.isSigningIn) {
        when {
            account.isSigningIn -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            account.isSignedInWithGoogle && photoUrl != null -> AsyncImage(
                model = photoUrl,
                contentDescription = "Tu cuenta",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
            account.isSignedInWithGoogle -> Icon(
                painterResource(R.drawable.ic_account_circle_filled),
                contentDescription = "Tu cuenta",
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(painterResource(R.drawable.ic_account_circle), contentDescription = "Iniciar sesión con Google")
        }
    }
}

/** Resumen de la cuenta vinculada; mismo estilo que el diálogo de monedas. */
@Composable
fun AccountDialog(account: AccountUiState, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    val user = account.user ?: return
    val count = account.wishlist.size
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (user.photoUrl != null) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                Icon(painterResource(R.drawable.ic_account_circle_filled), contentDescription = null)
            }
        },
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
