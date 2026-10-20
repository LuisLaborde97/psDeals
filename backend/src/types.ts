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
  /** Géneros localizados por la tienda (ej. "Acción", "Terror"). Vacío si no se pudieron obtener. */
  genres: string[];
}
