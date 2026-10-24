package co.golab.deals

import co.golab.deals.data.AppPreferences
import co.golab.deals.model.Country
import co.golab.deals.model.StorePlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los temas de FCM los nombra también el backend (notify.ts storeTopic y DISCOUNT_BUCKETS): si no coinciden, no llega nada. */
class StoreTopicsTest {
    @Test fun `tema por pais, tienda y umbral`() {
        assertEquals("deals_co_xbox_50", AppPreferences.topicFor(Country.COLOMBIA, StorePlatform.XBOX, 50))
        assertEquals("deals_mx_steam_100", AppPreferences.topicFor(Country.MEXICO, StorePlatform.STEAM, 100))
    }

    @Test fun `umbrales iguales a los del backend`() =
        assertEquals(listOf(30, 50, 70, 100), AppPreferences.DISCOUNT_OPTIONS)

    @Test fun `el umbral 100 se muestra como solo gratis`() {
        assertEquals("Solo gratis", AppPreferences.discountLabel(100))
        assertEquals("-30 %", AppPreferences.discountLabel(30))
    }
}
