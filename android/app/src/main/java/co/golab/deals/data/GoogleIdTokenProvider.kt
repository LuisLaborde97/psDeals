package co.golab.deals.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Obtiene el ID token de Google con Credential Manager (el GoogleSignIn de play-services-auth está obsoleto). */
class GoogleIdTokenProvider {

    /**
     * Muestra la hoja "Continuar con Google" y devuelve el ID token para Firebase.
     *
     * @param activityContext Credential Manager necesita una Activity para mostrar la UI.
     * @param webClientId cliente OAuth "Web" del proyecto: `R.string.default_web_client_id`, que el plugin
     *   de google-services genera solo si google-services.json incluye los clientes OAuth.
     * @throws androidx.credentials.exceptions.GetCredentialCancellationException si el usuario cierra la hoja.
     * @throws NoGoogleAccountException si el teléfono no tiene ninguna cuenta de Google.
     */
    suspend fun requestIdToken(activityContext: Context, webClientId: String): String {
        // GetSignInWithGoogleOption es el flujo de botón explícito: muestra todas las cuentas del teléfono,
        // no solo las que ya usaron la app.
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        } catch (e: NoCredentialException) {
            throw NoGoogleAccountException(e)
        }
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Credencial inesperada: ${credential.type}"
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}

/** No hay cuentas de Google en el dispositivo: la UI debería invitar a agregar una en Ajustes. */
class NoGoogleAccountException(cause: Throwable) : Exception("No hay cuentas de Google en el dispositivo", cause)
