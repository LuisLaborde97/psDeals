package co.golab.deals.data

import android.content.Context
import androidx.core.content.edit
import co.golab.deals.model.Country
import co.golab.deals.model.StorePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Preferencias de este teléfono. En el dispositivo y no en la cuenta: los temas de FCM son por instalación,
 * y así funcionan igual sin iniciar sesión.
 *
 * @param country país de las ofertas (y de las alertas): la colección de Firestore y los temas de FCM dependen de él.
 * @param alertStores tiendas de las que llegan ofertas del día; también van primero en los filtros. Las alertas de
 *   la lista de deseos llegan siempre, de cualquier tienda: el usuario guardó ese juego a propósito.
 * @param minDiscount uno de [DISCOUNT_OPTIONS]; 100 = solo juegos gratis.
 * @param selectedStores última selección de tiendas en la lista (vacío = "Todas"), para abrir donde se dejó.
 */
data class StorePreferences(
    val onboardingDone: Boolean,
    val country: Country,
    val alertStores: Set<StorePlatform>,
    val minDiscount: Int,
    val selectedStores: Set<StorePlatform>,
)

class AppPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<StorePreferences> = _state.asStateFlow()

    /** Fin de la bienvenida: las tiendas elegidas quedan como filtro inicial y como origen de las alertas. */
    fun completeOnboarding(country: Country, stores: Set<StorePlatform>, minDiscount: Int) = save {
        // Con todas elegidas, el filtro inicial es "Todas" (equivale y se lee mejor que cuatro chips marcados).
        val selected = if (stores.size == StorePlatform.entries.size) emptySet() else stores
        it.copy(onboardingDone = true, country = country, alertStores = stores, minDiscount = minDiscount, selectedStores = selected)
    }

    /** Desde Ajustes: cambia las ofertas que se muestran y los temas de alertas (PSDealsApp los resincroniza). */
    fun setCountry(country: Country) {
        if (country != _state.value.country) save { it.copy(country = country) }
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
            putString(KEY_COUNTRY, value.country.code)
            putStringSet(KEY_ALERT_STORES, value.alertStores.mapTo(HashSet()) { it.id })
            putInt(KEY_MIN_DISCOUNT, value.minDiscount)
            putStringSet(KEY_SELECTED_STORES, value.selectedStores.mapTo(HashSet()) { it.id })
        }
    }

    private fun read(): StorePreferences {
        fun stores(key: String, default: Set<StorePlatform>): Set<StorePlatform> =
            prefs.getStringSet(key, null)?.mapNotNullTo(LinkedHashSet()) { StorePlatform.fromId(it) } ?: default
        val onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        return StorePreferences(
            onboardingDone = onboardingDone,
            // Quien ya pasó la bienvenida antes de los países usaba Colombia; para el resto, el país detectado.
            country = Country.fromCode(prefs.getString(KEY_COUNTRY, null))
                ?: if (onboardingDone) Country.DEFAULT else CountryDetector.detect(context),
            // Sin elegir aún (o al actualizar desde una versión anterior): lo mismo que recibía antes, todas desde -50 %.
            alertStores = stores(KEY_ALERT_STORES, StorePlatform.entries.toSet()),
            minDiscount = prefs.getInt(KEY_MIN_DISCOUNT, DEFAULT_MIN_DISCOUNT).takeIf { it in DISCOUNT_OPTIONS } ?: DEFAULT_MIN_DISCOUNT,
            selectedStores = stores(KEY_SELECTED_STORES, setOf(StorePlatform.PLAYSTATION)),
        )
    }

    companion object {
        private const val PREFS = "store_preferences"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_COUNTRY = "country"
        private const val KEY_ALERT_STORES = "alert_stores"
        private const val KEY_MIN_DISCOUNT = "min_discount"
        private const val KEY_SELECTED_STORES = "selected_stores"

        /** Deben coincidir con DISCOUNT_BUCKETS del backend (notify.ts). */
        val DISCOUNT_OPTIONS = listOf(30, 50, 70, 100)
        const val DEFAULT_MIN_DISCOUNT = 50

        /** "deals_mx_xbox_50"; debe coincidir con storeTopic() del backend. */
        fun topicFor(country: Country, platform: StorePlatform, minDiscount: Int) =
            "deals_${country.code}_${platform.id}_$minDiscount"

        /** Texto de cada opción de "Avisarme desde". */
        fun discountLabel(minDiscount: Int) = if (minDiscount >= 100) "Solo gratis" else "-$minDiscount %"
    }
}
