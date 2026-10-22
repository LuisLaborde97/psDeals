import { randomBytes } from 'node:crypto';
import { errMsg, formatPrice, withRetries } from './scraper.js';
import type { Deal, ScrapeResult } from './types.js';

/**
 * API con la que xbox.com arma su catálogo. El canal GameDeals es la sección "Ofertas" de la tienda:
 * solo trae juegos rebajados, con precios del mercado del locale (es-CO → COP) y géneros localizados.
 */
const BROWSE_URL = 'https://emerald.xboxservices.com/xboxcomfd/browse';
const DEALS_CHANNEL = 'DynamicChannel.GameDeals';
const STORE_URL = 'https://www.xbox.com';
/** ~1.100 ofertas en CO: el tope las cubre todas; el recorrido termina cuando no hay más páginas. */
const MAX_RESULTS = Number(process.env.XBOX_MAX_RESULTS ?? 2000);
const REQUEST_TIMEOUT_MS = 30_000;
/** El CDN de imágenes de Microsoft redimensiona con `?w=`: ~100 KB en vez de ~225 KB por portada. */
const IMAGE_WIDTH = 440;

interface XboxPrice {
  discountPercentage?: number;
  listPrice?: number;
  msrp?: number;
  currency?: string;
  eligibilityInfo?: { eligibility?: string };
  /** Fin de esta disponibilidad de precio, "MM/DD/YYYY HH:mm:ss" en UTC. */
  endDateUtc?: string;
}

interface ProductSummary {
  productId: string;
  title?: string;
  productKind?: string;
  categories?: string[];
  images?: { poster?: { url: string }; boxArt?: { url: string } };
  specificPrices?: { purchaseable?: XboxPrice[] };
}

interface BrowseResponse {
  channels?: Record<string, { products?: { productId: string }[]; encodedCT?: string; totalItems?: number }>;
  productSummaries?: Record<string, ProductSummary>;
}

/** Ofertas de la tienda de Xbox para la región de la PS Store (`es-co` → locale es-CO, precios en COP). */
export async function scrapeXboxDeals(region: string): Promise<ScrapeResult> {
  const [lang, country = 'us'] = region.split('-');
  const locale = `${lang}-${country.toUpperCase()}`;
  // Popularidad en vez de descuento: ordenar por % trae primero juegos poco conocidos.
  const filters = Buffer.from(JSON.stringify({ orderby: { id: 'orderby', choices: [{ id: 'MostPopular desc' }] } })).toString('base64');

  const deals = new Map<string, Deal>();
  let continuation: string | undefined;
  let total = 0;
  let complete = true;
  // Tope de páginas (25 productos c/u) por si el token de continuación se repitiera.
  const maxPages = Math.ceil(MAX_RESULTS / 25) + 5;
  for (let page = 1; page <= maxPages && deals.size < MAX_RESULTS; page++) {
    const res = await withRetries(`xbox browse p.${page}`, () =>
      postJson<BrowseResponse>(`${BROWSE_URL}?locale=${locale}`, {
        Filters: filters,
        EncodedFilters: filters,
        ReturnFilters: false,
        ChannelId: DEALS_CHANNEL,
        ChannelKeyToBeUsedInResponse: DEALS_CHANNEL,
        ...(continuation && { EncodedCT: continuation }),
      }),
    );
    if (!res) complete = false; // agotó reintentos: faltan páginas del canal de ofertas
    const channel = Object.values(res?.channels ?? {})[0];
    const products = Object.values(res?.productSummaries ?? {});
    if (!channel || products.length === 0) break;
    total = channel.totalItems ?? total;

    for (const p of products) {
      const deal = toDeal(p, region, locale);
      if (deal && deals.size < MAX_RESULTS) deals.set(deal.id, deal);
    }
    continuation = channel.encodedCT;
    if (!continuation) break;
  }
  console.log(`[xbox] ${deals.size} con descuento (canal de ofertas: ${total} en total)`);
  return { deals: [...deals.values()], complete };
}

function toDeal(p: ProductSummary, region: string, locale: string): Deal | null {
  if (!p.productId || !p.title || p.productKind !== 'Game') return null;
  // Solo el precio público: los que exigen Game Pass u otra suscripción traen otra eligibility.
  const price = p.specificPrices?.purchaseable?.find(
    (x) => (x.discountPercentage ?? 0) > 0 && (x.eligibilityInfo?.eligibility ?? 'None') === 'None',
  );
  if (!price?.currency || price.listPrice === undefined || !price.msrp) return null;

  const image = p.images?.poster?.url ?? p.images?.boxArt?.url;
  const storeUrl = `${STORE_URL}/${locale}/games/store/${slugify(p.title)}/${p.productId}`;
  return {
    id: `xbox-${p.productId}`,
    title: p.title.trim(),
    originalPrice: price.msrp,
    discountedPrice: price.listPrice,
    originalPriceText: formatPrice(price.msrp, price.currency),
    discountedPriceText: formatPrice(price.listPrice, price.currency),
    // La API da fracciones (ej. 90.00045 %) por el redondeo de los precios.
    discountPercent: Math.round(price.discountPercentage ?? 0),
    imageUrl: image ? `${image}?w=${IMAGE_WIDTH}` : null,
    productUrl: storeUrl,
    platform: 'xbox',
    storeUrl,
    region,
    genres: p.categories ?? [],
    endDate: parseXboxDate(price.endDateUtc),
  };
}

/**
 * "11/05/2026 14:59:59" (UTC) → ms. Los precios sin fin real traen fechas lejanas (ej. año 9998): se
 * ignoran las de más de un año, que no describen el fin de una rebaja.
 */
function parseXboxDate(text: string | undefined): number | null {
  const m = text?.match(/^(\d{2})\/(\d{2})\/(\d{4}) (\d{2}):(\d{2}):(\d{2})$/);
  if (!m) return null;
  const [, mo, d, y, h, mi, s] = m.map(Number);
  const ms = Date.UTC(y, mo - 1, d, h, mi, s);
  return Number.isFinite(ms) && ms - Date.now() < 366 * 24 * 60 * 60 * 1000 ? ms : null;
}

/** xbox.com ignora el slug (solo usa el ID), pero uno legible hace el enlace más claro. */
function slugify(title: string): string {
  return (
    title
      .normalize('NFD')
      .replace(/\p{Mn}+/gu, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '') || 'juego'
  );
}

async function postJson<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (PSDeals scraper)',
      'x-ms-api-version': '1.1',
      // Correlation vector obligatorio; el servidor solo exige que exista.
      'MS-CV': `${randomBytes(12).toString('base64url')}.0`,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} en ${new URL(url).pathname}`);
  try {
    return (await res.json()) as T;
  } catch (err) {
    throw new Error(`respuesta no JSON en ${new URL(url).pathname}: ${errMsg(err)}`);
  }
}
