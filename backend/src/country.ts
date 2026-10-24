/**
 * Países: cada ejecución del scraper trabaja con uno, derivado de la región de la PS Store (es-co → co, es-mx → mx).
 *
 * Colombia conserva los nombres de siempre (colección `deals`, `config/scraper`, IDs de wishlist sin prefijo): las
 * versiones de la app anteriores a los países los siguen leyendo sin ver ofertas de otro país. Los demás países
 * llevan sufijo o prefijo. Debe coincidir con Country.kt de la app.
 */
export const LEGACY_COUNTRY = 'co';

/** "es-mx" → "mx". */
export function countryOf(region: string): string {
  return (region.split('-')[1] ?? LEGACY_COUNTRY).toLowerCase();
}

/** `deals` para Colombia, `deals_mx` para México. */
export function dealsCollection(country: string): string {
  return country === LEGACY_COUNTRY ? 'deals' : `deals_${country}`;
}

/** Documento de `config` por país: `scraper` / `scraper_mx`, `genres` / `genres_mx`. */
export function configDocId(base: string, country: string): string {
  return country === LEGACY_COUNTRY ? base : `${base}_${country}`;
}

/**
 * Clave de una oferta en `users/{uid}.wishlist`. Los IDs se repiten entre países (steam-123 existe en ambos), así que
 * fuera de Colombia llevan el país delante: `mx:steam-123`.
 */
export function wishlistKey(country: string, dealId: string): string {
  return country === LEGACY_COUNTRY ? dealId : `${country}:${dealId}`;
}
