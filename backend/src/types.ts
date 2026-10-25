/** Tiendas soportadas por el agregador. */
export type Platform = 'playstation' | 'steam' | 'epic' | 'xbox';

export interface Deal {
  /** ID del producto en la PS Store (último segmento de /product/{id}); se usa como ID del documento. */
  id: string;
  title: string;
  originalPrice: number | null;
  discountedPrice: number | null;
  /** Precios tal como los muestra la tienda (ej. "US$39.99"), útiles para la UI. */
  originalPriceText: string;
  discountedPriceText: string;
  discountPercent: number;
  imageUrl: string | null;
  productUrl: string;
  /** Tienda de origen de la oferta. */
  platform: Platform;
  /** Enlace directo a la página de compra en la tienda de origen. */
  storeUrl: string;
  region: string;
  /**
   * Fin de la rebaja en epoch millis, si la tienda lo publica (Steam, Epic, ITAD, Xbox); null si no
   * (PlayStation). La app oculta las ofertas vencidas aunque el scraper aún no haya vuelto a correr.
   */
  endDate: number | null;
  /** Géneros localizados por la tienda (ej. "Acción", "Terror"). Vacío si no se pudieron obtener. */
  genres: string[];
  /**
   * Contenido sexual explícito (Steam: descriptores 3 y 4; Epic vía ITAD: `mature`). No se descarta: la variante de
   * la app para Google Play lo oculta (su política no lo permite) y la de GitHub lo muestra. Nunca va en pushes.
   */
  explicit: boolean;
}

/** Lo que devuelve cada motor de tienda. */
export interface ScrapeResult {
  deals: Deal[];
  /**
   * true si se recorrió todo el listado de la tienda (ninguna petición agotó reintentos). Solo entonces
   * una oferta ausente significa que la rebaja terminó; con un listado parcial no se puede saber.
   */
  complete: boolean;
}
