import { errMsg, formatPrice, withRetries } from './scraper.js';
import type { Deal, ScrapeResult } from './types.js';

/**
 * Epic cerró su GraphQL público (graphql.epicgames.com da 404 y store.epicgames.com/graphql exige el
 * desafío de Cloudflare), así que se combinan dos fuentes sin bloqueo:
 * - IsThereAnyDeal: rebajas de la Epic Games Store por país (requiere ITAD_API_KEY).
 * - freeGamesPromotions de Epic: la colección de juegos gratis de la semana y sus rebajas, con enlace directo.
 */
const ITAD_API_URL = 'https://api.isthereanydeal.com';
/** ID de la Epic Games Store en IsThereAnyDeal (GET /service/shops/v1). */
const ITAD_EPIC_SHOP_ID = 16;
/** Máximo que acepta /deals/v2 por petición; con sort=rank vienen primero los juegos más populares. */
const MAX_RESULTS = Number(process.env.EPIC_MAX_RESULTS ?? 1000);
/** Máximo que acepta /deals/v2 por petición: se pagina con offset hasta juntar [MAX_RESULTS] juegos. */
const ITAD_PAGE_SIZE = 200;
/** Tope de páginas por si hasMore no llegara nunca a false. */
const ITAD_MAX_PAGES = 30;
const FREE_GAMES_URL = 'https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions';
const STORE_URL = 'https://store.epicgames.com';
/** Epic no tiene es-CO; es-MX es su español latinoamericano. */
const EPIC_LOCALES: Record<string, string> = { es: 'es-MX', en: 'en-US' };
const REQUEST_TIMEOUT_MS = 30_000;
/** Orden de preferencia para la portada; las verticales se recortan mejor en la tarjeta. */
const EPIC_IMAGE_TYPES = ['OfferImageTall', 'Thumbnail', 'OfferImageWide'];
const GAME_OFFER_TYPES = new Set(['BASE_GAME', 'BUNDLE']);

interface ItadPrice {
  amount: number;
  currency: string;
}

interface ItadDeal {
  title: string;
  type: 'game' | 'dlc' | 'package' | null;
  assets?: { boxart?: string; banner600?: string; banner400?: string };
  /** expiry: fin de la rebaja (ISO 8601) o null si ITAD no lo conoce. */
  deal: { price: ItadPrice; regular: ItadPrice; cut: number; url: string; expiry?: string | null };
}

interface EpicElement {
  title: string;
  offerType?: string;
  productSlug?: string | null;
  keyImages?: { type: string; url: string }[];
  catalogNs?: { mappings?: { pageSlug: string; pageType: string }[] | null } | null;
  offerMappings?: { pageSlug: string; pageType: string }[] | null;
  price?: { totalPrice?: { originalPrice: number; discountPrice: number; currencyCode: string; currencyInfo?: { decimals: number } } };
  promotions?: { promotionalOffers?: { promotionalOffers: { startDate: string; endDate: string }[] }[] } | null;
}

/** Ofertas de Epic para la región de la PS Store (`es-co` → país CO, precios en COP). */
export async function scrapeEpicDeals(region: string): Promise<ScrapeResult> {
  const [lang, country = 'us'] = region.split('-');
  const cc = country.toUpperCase();
  const locale = EPIC_LOCALES[lang] ?? 'en-US';

  const [officialResult, itadResult] = await Promise.all([
    fetchFreeGamesCollection(region, cc, locale),
    fetchItadDeals(region, cc),
  ]);
  const official = officialResult.deals;
  const itad = itadResult.deals;

  // La misma oferta puede venir de las dos fuentes: gana la oficial, que trae el enlace directo a Epic.
  const deals = new Map<string, Deal>();
  for (const d of [...itad, ...official]) deals.set(d.id, d);
  console.log(`[epic] ${deals.size} ofertas (${official.length} de la colección oficial, ${itad.length} de IsThereAnyDeal)`);
  return { deals: [...deals.values()], complete: officialResult.complete && itadResult.complete };
}

async function fetchItadDeals(region: string, cc: string): Promise<ScrapeResult> {
  const key = process.env.ITAD_API_KEY;
  if (!key) {
    console.warn('[epic] falta ITAD_API_KEY: solo se usarán los juegos gratis de la colección oficial');
    // Completo a propósito: sin clave no hay rebajas de ITAD en ninguna ejecución, no es un fallo puntual.
    return { deals: [], complete: true };
  }
  // ITAD solo sigue los precios regionales de Epic en algunos países (BR, AR…); para CO, MX o CL
  // devuelve el precio de la tienda de EE. UU. en USD, no el COP que cobra Epic en Colombia.
  // Por popularidad (rank) y de a 200: se recorren páginas hasta juntar MAX_RESULTS juegos o agotar la lista.
  const list: ItadDeal[] = [];
  let complete = true;
  let offset = 0;
  for (let page = 1; page <= ITAD_MAX_PAGES; page++) {
    const params = new URLSearchParams({
      country: cc,
      shops: String(ITAD_EPIC_SHOP_ID),
      limit: String(ITAD_PAGE_SIZE),
      offset: String(offset),
      // Sin orden, ITAD devuelve casi solo juegos de relleno; rank = popularidad.
      sort: 'rank',
      mature: 'true', // sin esto ITAD omite los juegos para adultos, que PlayStation y Steam sí incluyen
    });
    const res = await withRetries(`epic ITAD /deals/v2 offset=${offset}`, () =>
      fetchJson<{ list?: ItadDeal[]; hasMore?: boolean; nextOffset?: number }>(`${ITAD_API_URL}/deals/v2?${params}`, {
        'ITAD-API-Key': key,
      }),
    );
    if (!res) {
      complete = false; // agotó reintentos: faltan páginas
      break;
    }
    list.push(...(res.list ?? []));
    // La lista trae DLC y paquetes: cuenta solo juegos para el tope.
    if (list.filter((d) => d.type === 'game').length >= MAX_RESULTS || !res.hasMore || res.nextOffset === undefined) break;
    offset = res.nextOffset;
  }
  const deals = list
    .filter((d) => d.type === 'game' && d.deal.cut > 0)
    .slice(0, MAX_RESULTS)
    .map((d): Deal => {
      const { price, regular, cut, url, expiry } = d.deal;
      return {
        id: epicId(d.title),
        title: d.title.trim(),
        originalPrice: regular.amount,
        discountedPrice: price.amount,
        originalPriceText: formatPrice(regular.amount, regular.currency),
        discountedPriceText: formatPrice(price.amount, price.currency),
        discountPercent: cut,
        imageUrl: d.assets?.boxart ?? d.assets?.banner600 ?? d.assets?.banner400 ?? null,
        // Enlace de ITAD: redirige a la página del juego en Epic.
        productUrl: url,
        platform: 'epic',
        storeUrl: url,
        region,
        genres: [], // /deals/v2 no trae etiquetas
        endDate: parseDate(expiry),
      };
    });
  console.log(`[epic] IsThereAnyDeal: ${deals.length} juegos con descuento de ${list.length} ofertas`);
  return { deals, complete };
}

/** La colección "freegames" de Epic: juegos gratis de la semana y algunos con descuento por tiempo limitado. */
async function fetchFreeGamesCollection(region: string, cc: string, locale: string): Promise<ScrapeResult> {
  const params = new URLSearchParams({ locale, country: cc, allowCountries: cc });
  const res = await withRetries('epic freeGamesPromotions', () =>
    fetchJson<{ data?: { Catalog?: { searchStore?: { elements?: EpicElement[] } } } }>(`${FREE_GAMES_URL}?${params}`),
  );
  const elements = res?.data?.Catalog?.searchStore?.elements ?? [];
  const now = Date.now();
  const deals: Deal[] = [];
  for (const e of elements) {
    const price = e.price?.totalPrice;
    const slug = pageSlug(e);
    // La colección también lista los próximos gratis (promoción futura) y complementos: se omiten.
    const active = e.promotions?.promotionalOffers
      ?.flatMap((p) => p.promotionalOffers)
      .find((o) => Date.parse(o.startDate) <= now && now < Date.parse(o.endDate));
    if (!active || !price || !slug || !GAME_OFFER_TYPES.has(e.offerType ?? '')) continue;
    if (price.discountPrice >= price.originalPrice || price.originalPrice <= 0) continue;

    const divisor = 10 ** (price.currencyInfo?.decimals ?? 2);
    const original = price.originalPrice / divisor;
    const discounted = price.discountPrice / divisor;
    const storeUrl = `${STORE_URL}/${locale}/p/${slug}`;
    deals.push({
      id: epicId(e.title),
      title: e.title.trim(),
      originalPrice: original,
      discountedPrice: discounted,
      originalPriceText: formatPrice(original, price.currencyCode),
      discountedPriceText: formatPrice(discounted, price.currencyCode),
      discountPercent: Math.round((1 - discounted / original) * 100),
      imageUrl: EPIC_IMAGE_TYPES.map((t) => e.keyImages?.find((k) => k.type === t)?.url).find(Boolean) ?? null,
      productUrl: storeUrl,
      platform: 'epic',
      storeUrl,
      region,
      genres: [], // solo trae IDs de etiquetas, sin nombres
      endDate: parseDate(active.endDate),
    });
  }
  console.log(`[epic] colección oficial: ${deals.length} con descuento de ${elements.length} elementos`);
  return { deals, complete: res !== null };
}

function parseDate(iso: string | null | undefined): number | null {
  const ms = iso ? Date.parse(iso) : NaN;
  return Number.isFinite(ms) ? ms : null;
}

function pageSlug(e: EpicElement): string | null {
  const home = (mappings: { pageSlug: string; pageType: string }[] | null | undefined) =>
    mappings?.find((m) => m.pageType === 'productHome')?.pageSlug;
  return home(e.catalogNs?.mappings) ?? home(e.offerMappings) ?? e.productSlug?.replace(/\/home$/, '') ?? null;
}

/**
 * ID a partir del título y no del ID de cada fuente: así un juego conserva su documento (y no se
 * vuelve a notificar como nuevo) si pasa de una rebaja de ITAD a la colección oficial o viceversa.
 */
function epicId(title: string): string {
  const slug = title
    .normalize('NFD')
    .replace(/\p{Mn}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  return `epic-${slug}`;
}

async function fetchJson<T>(url: string, headers: Record<string, string> = {}): Promise<T> {
  const res = await fetch(url, {
    headers: { 'User-Agent': 'PSDeals/1.0 (+https://github.com/LuisLaborde97/psDeals)', ...headers },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} en ${new URL(url).pathname}`);
  try {
    return (await res.json()) as T;
  } catch (err) {
    throw new Error(`respuesta no JSON en ${new URL(url).pathname}: ${errMsg(err)}`);
  }
}
