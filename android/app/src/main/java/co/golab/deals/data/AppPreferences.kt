package co.golab.deals.data

import android.content.Context
import androidx.core.content.edit
import co.golab.deals.model.StorePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Preferencias de este teléfono. En el dispositivo y no en la cuenta: los temas de FCM son por instalación,
 * y así funcionan igual sin iniciar sesión.
 *
 * @param alertStores tiendas de las que llegan ofertas del día; también van primero en los filtros. Las alertas de
 *   la lista de deseos llegan siempre, de cualquier tienda: el usuario guardó ese juego a propósito.
 * @param minDiscount uno de [DISCOUNT_OPTIONS]; 100 = solo juegos gratis.
 * @param selectedStores última selección de tiendas en la lista (vacío = "Todas"), para abrir donde se dejó.
 */
data class StorePreferences(
    val onboardingDone: Boolean,
    val alertStores: Set<StorePlatform>,
    val minDiscount: Int,
    val selectedStores: Set<StorePlatform>,
)

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<StorePreferences> = _state.asStateFlow()

    /** Fin de la bienvenida: las tiendas elegidas quedan como filtro inicial y como origen de las alertas. */
    fun completeOnboarding(stores: Set<StorePlatform>, minDiscount: Int) = save {
        // Con todas elegidas, el filtro inicial es "Todas" (equivale y se lee mejor que cuatro chips marcados).
        val selected = if (stores.size == StorePlatform.entries.size) emptySet() else stores
        it.copy(onboardingDone = true, alertStores = stores, minDiscount = minDiscount, selectedStores = selected)
    }

    fun setAlerts(stores: Set<StorePlatform>, minDiscount: Int) = save { it.copy(alertStores = stores, minDiscount = minDiscount) }

    fun setSelectedStores(stores: Set<StorePlatform>) {
        if (stores != _state.value.selectedStores) save { it.copy(selectedStores = stores) }
    }

    private fun save(change: (StorePreferences) -> StorePreferences) {
        _state.update(change)
        val value = _state.value
        prefs.edit {
            putBoolean(KEY_ONBOARDING_DONE, value.onboardingDone)
            putStringSet(KEY_ALERT_STORES, value.alertStores.mapTo(HashSet()) { it.id })
            putInt(KEY_MIN_DISCOUNT, value.minDiscount)
            putStringSet(KEY_SELECTED_STORES, value.selectedStores.mapTo(HashSet()) { it.id })
        }
    }

    private fun read(): StorePreferences {
        fun stores(key: String, default: Set<StorePlatform>): Set<StorePlatform> =
            prefs.getStringSet(key, null)?.mapNotNullTo(LinkedHashSet()) { StorePlatform.fromId(it) } ?: default
        return StorePreferences(
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
            // Sin elegir aún (o al actualizar desde una versión anterior): lo mismo que recibía antes, todas desde -50 %.
            alertStores = stores(KEY_ALERT_STORES, StorePlatform.entries.toSet()),
            minDiscount = prefs.getInt(KEY_MIN_DISCOUNT, DEFAULT_MIN_DISCOUNT).takeIf { it in DISCOUNT_OPTIONS } ?: DEFAULT_MIN_DISCOUNT,
            selectedStores = stores(KEY_SELECTED_STORES, setOf(StorePlatform.PLAYSTATION)),
        )
    }

    companion object {
        private const val PREFS = "store_preferences"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ALERT_STORES = "alert_stores"
        private const val KEY_MIN_DISCOUNT = "min_discount"
        private const val KEY_SELECTED_STORES = "selected_stores"

        /** Deben coincidir con DISCOUNT_BUCKETS del backend (notify.ts). */
        val DISCOUNT_OPTIONS = listOf(30, 50, 70, 100)
        const val DEFAULT_MIN_DISCOUNT = 50

        /** "deals_xbox_50"; debe coincidir con storeTopic() del backend. */
        fun topicFor(platform: StorePlatform, minDiscount: Int) = "deals_${platform.id}_$minDiscount"

        /** Texto de cada opción de "Avisarme desde". */
        fun discountLabel(minDiscount: Int) = if (minDiscount >= 100) "Solo gratis" else "-$minDiscount %"
    }
}
