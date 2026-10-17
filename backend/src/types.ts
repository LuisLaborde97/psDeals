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
  region: string;
}
