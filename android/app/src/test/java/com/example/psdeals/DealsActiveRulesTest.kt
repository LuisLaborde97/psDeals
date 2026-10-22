package com.example.psdeals

import com.example.psdeals.data.DealsRepository
import com.example.psdeals.model.Deal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas que deciden si una oferta sigue vigente (el backend nunca borra ofertas). */
class DealsActiveRulesTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L
    private val lastRun = now - 2 * hour // la última ejecución completa de la tienda fue hace 2 h

    private fun deal(lastSeenAt: Long?, endDate: Long? = null) = Deal(
        id = "x", title = "Juego", originalPriceText = "", discountedPriceText = "", discountPercent = 50,
        imageUrl = null, productUrl = "https://example.com", lastSeenAt = lastSeenAt, endDate = endDate,
    )

    @Test fun `vista en la ultima ejecucion sigue vigente`() =
        assertTrue(DealsRepository.isActive(deal(lastSeenAt = lastRun - 10 * 60 * 1000), now, lastRun))

    @Test fun `ausente en la ultima ejecucion se oculta aunque tenga menos de 3 dias`() =
        assertFalse(DealsRepository.isActive(deal(lastSeenAt = lastRun - 24 * hour), now, lastRun))

    @Test fun `fecha de fin vencida se oculta aunque se haya visto en la ultima ejecucion`() =
        assertFalse(DealsRepository.isActive(deal(lastSeenAt = lastRun, endDate = now - 1), now, lastRun))

    @Test fun `fecha de fin futura no la oculta`() =
        assertTrue(DealsRepository.isActive(deal(lastSeenAt = lastRun, endDate = now + hour), now, lastRun))

    @Test fun `sin marca de ejecucion se usa el respaldo de 3 dias`() {
        assertTrue(DealsRepository.isActive(deal(lastSeenAt = now - 48 * hour), now, lastRunAt = null))
        assertFalse(DealsRepository.isActive(deal(lastSeenAt = now - 80 * hour), now, lastRunAt = null))
    }

    @Test fun `tienda que dejo de ejecutarse cae por el respaldo de 3 dias`() =
        // lastRunAt viejo (la tienda no corre hace 4 días): la oferta se vio en esa corrida, pero ya pasaron 3 días.
        assertFalse(DealsRepository.isActive(deal(lastSeenAt = now - 96 * hour), now, lastRunAt = now - 96 * hour))
}
