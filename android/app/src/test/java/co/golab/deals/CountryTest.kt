package co.golab.deals

import co.golab.deals.model.Country
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mismas reglas que backend/src/country.ts: Colombia conserva los nombres de antes de los países. */
class CountryTest {
    @Test fun `colombia usa la coleccion y la config de siempre`() {
        assertEquals("deals", Country.COLOMBIA.dealsCollection)
        assertEquals("scraper", Country.COLOMBIA.configDoc("scraper"))
        assertEquals("steam-1", Country.COLOMBIA.wishlistKey("steam-1"))
    }

    @Test fun `mexico lleva sufijo y prefijo`() {
        assertEquals("deals_mx", Country.MEXICO.dealsCollection)
        assertEquals("genres_mx", Country.MEXICO.configDoc("genres"))
        assertEquals("mx:steam-1", Country.MEXICO.wishlistKey("steam-1"))
    }

    @Test fun `las claves de wishlist se separan por pais`() {
        assertEquals(Country.MEXICO to "steam-1", Country.parseWishlistKey("mx:steam-1"))
        assertEquals(Country.COLOMBIA to "steam-1", Country.parseWishlistKey("steam-1"))
        // Los IDs de PlayStation no llevan ":": no se confunden con un prefijo de país.
        assertEquals(Country.COLOMBIA to "UP0082-CUSA11230_00-X", Country.parseWishlistKey("UP0082-CUSA11230_00-X"))
    }
}
