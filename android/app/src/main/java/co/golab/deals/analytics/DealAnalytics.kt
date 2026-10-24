package co.golab.deals.analytics

import co.golab.deals.model.Deal
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent

/**
 * Eventos de interés en las ofertas, enviados a Google Analytics para Firebase (nada se escribe en Firestore).
 * Analytics los agrupa y envía en segundo plano: llamar desde el hilo principal no bloquea la UI.
 */
object DealAnalytics {
    private const val EVENT_DEAL_OPENED = "oferta_abierta"
    private const val EVENT_STORE_CLICK = "clic_ir_a_tienda"
    private const val PARAM_GAME_TITLE = "game_title"
    private const val PARAM_PLATFORM = "platform"
    private const val PARAM_COUNTRY = "country"
    /** Analytics descarta los valores de texto de más de 100 caracteres. */
    private const val MAX_PARAM_LENGTH = 100

    /** El usuario abrió el detalle de una oferta. */
    fun logDealOpened(deal: Deal) = log(EVENT_DEAL_OPENED, deal)

    /** El usuario salió hacia la tienda de la oferta: la señal más cercana a una intención de compra. */
    fun logStoreClick(deal: Deal) = log(EVENT_STORE_CLICK, deal)

    private fun log(event: String, deal: Deal) {
        Firebase.analytics.logEvent(event) {
            param(PARAM_GAME_TITLE, deal.title.take(MAX_PARAM_LENGTH))
            // Hay cuatro tiendas: sin esto no se distingue un clic a PlayStation de uno a Steam.
            param(PARAM_PLATFORM, deal.platform)
            param(PARAM_COUNTRY, deal.country.code)
        }
    }
}
