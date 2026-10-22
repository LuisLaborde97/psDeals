import { errMsg, withRetries } from './scraper.js';
import type { Deal, ScrapeResult } from './types.js';

const STORE_URL = 'https://store.steampowered.com';
const API_URL = 'https://api.steampowered.com';
const ASSETS_URL = 'https://shared.akamai.steamstatic.com/store_item_assets/';
/**
 * Steam tiene ~9.000 juegos en oferta; el buscador los ordena por relevancia, así que bastan los
 * primeros 1.000 para cubrir lo que interesa sin llenar Firestore (y la descarga de la app) de títulos desconocidos.
 */
const MAX_RESULTS = Number(process.env.STEAM_MAX_RESULTS ?? 1000);
/** Máximo que acepta el buscador por página. */
const SEARCH_PAGE_SIZE = 100;
/** `category1=998`: solo juegos (sin DLC, bandas sonoras ni software). */
const GAMES_CATEGORY = 998;
const REQUEST_TIMEOUT_MS = 30_000;
/** Orden de preferencia para la portada; library_capsule es vertical y se recorta mejor en la tarjeta. */
const IMAGE_ASSETS = ['library_capsule', 'main_capsule', 'header'] as const;
const MAX_GENRES = 3;
/**
 * Steam no expone géneros en estas APIs, solo etiquetas de la comunidad (incluye cosas como
 * "Gráficos bonitos"). Estas son las que equivalen a un género; el nombre localizado sale de GetTagList.
 */
const GENRE_TAG_IDS = new Set([
  19, 21, 122, 9, 599, 701, 699, 597, 492, 1667, 1774, 1663, 3814, 1743, 1625, 1664, 1662, 1695, 128,
  1773, 1687, 1666, 1752, 1716, 1628, 3810, 3799, 1741, 1646, 29482, 1684, 3942, 7178,
]);
/** Idioma de la tienda (`es-co` → `es`) al nombre que usa Steam en `l=` / `language`. */
const STEAM_LANGUAGES: Record<string, string> = { es: 'spanish', en: 'english' };

interface StoreItem {
  appid?: number;
  success?: number;
  name?: string;
  store_url_path?: string;
  tags?: { tagid: number; weight: number }[];
  assets?: Partial<Record<(typeof IMAGE_ASSETS)[number] | 'asset_url_format', string>>;
  best_purchase_option?: {
    final_price_in_cents?: string;
    original_price_in_cents?: string;
    formatted_final_price?: string;
    formatted_original_price?: string;
    discount_pct?: number;
    price_cannot_be_displayed_as_discount?: boolean;
    /** discount_end_date en segundos epoch. */
    active_discounts?: { discount_end_date?: number }[];
  };
}

/** Ofertas de Steam para la región de la PS Store (`es-co` → país CO, textos en español, precios en COP). */
export async function scrapeSteamDeals(region: string): Promise<ScrapeResult> {
  const [lang, country = 'us'] = region.split('-');
  const language = STEAM_LANGUAGES[lang] ?? 'english';
  const cc = country.toUpperCase();

  const search = await searchDiscountedAppIds(cc, language);
  const appIds = search.ids;
  const [fetched, tagNames] = await Promise.all([fetchItems(appIds, cc, language), fetchTagNames(language)]);
  const items = fetched.items;

  const deals = items.map((item) => toDeal(item, region, tagNames)).filter((d): d is Deal => d !== null);
  console.log(`[steam] ${deals.length} con descuento de ${appIds.length} juegos consultados`);
  // Sin los géneros (GetTagList) el listado sigue completo: solo faltan etiquetas.
  return { deals, complete: search.complete && fetched.complete };
}

/** El buscador web es el único listado público de ofertas; solo se usa para los appids, los datos vienen de GetItems. */
async function searchDiscountedAppIds(cc: string, language: string): Promise<{ ids: number[]; complete: boolean }> {
  const ids: number[] = [];
  let complete = true;
  for (let start = 0; start < MAX_RESULTS; start += SEARCH_PAGE_SIZE) {
    const params = new URLSearchParams({
      specials: '1',
      infinite: '1',
      category1: String(GAMES_CATEGORY),
      cc,
      l: language,
      start: String(start),
      count: String(Math.min(SEARCH_PAGE_SIZE, MAX_RESULTS - start)),
    });
    const page = await withRetries(`steam búsqueda start=${start}`, () =>
      fetchJson<{ results_html?: string; total_count?: number }>(`${STORE_URL}/search/results/?${params}`),
    );
    if (!page?.results_html) {
      complete = false; // agotó reintentos o respuesta inesperada: faltan páginas
      break;
    }

    const before = ids.length;
    // Cada fila es un <a class="search_result_row">; los paquetes y bundles traen varios appids o ninguno.
    for (const [tag] of page.results_html.matchAll(/<a [^>]*search_result_row[^>]*>/g)) {
      if (/data-ds-(packageid|bundleid)=/.test(tag)) continue;
      const appid = tag.match(/data-ds-appid="(\d+)"/)?.[1];
      if (appid) ids.push(Number(appid));
    }
    console.log(`[steam] búsqueda start=${start}: ${ids.length - before} juegos (total ${page.total_count ?? '?'})`);
    if (ids.length === before) break;
  }
  return { ids: [...new Set(ids)], complete };
}

async function fetchItems(appIds: number[], cc: string, language: string): Promise<{ items: StoreItem[]; complete: boolean }> {
  const items: StoreItem[] = [];
  let complete = true;
  for (let i = 0; i < appIds.length; i += SEARCH_PAGE_SIZE) {
    const input = {
      ids: appIds.slice(i, i + SEARCH_PAGE_SIZE).map((appid) => ({ appid })),
      context: { language, country_code: cc },
      data_request: { include_assets: true, include_tag_count: 20 },
    };
    const res = await withRetries(`steam GetItems lote ${i / SEARCH_PAGE_SIZE + 1}`, () =>
      fetchJson<{ response?: { store_items?: StoreItem[] } }>(
        `${API_URL}/IStoreBrowseService/GetItems/v1/?input_json=${encodeURIComponent(JSON.stringify(input))}`,
      ),
    );
    if (!res) complete = false;
    items.push(...(res?.response?.store_items ?? []));
  }
  return { items, complete };
}

/** Nombres localizados de las etiquetas; si falla, las ofertas quedan sin géneros en vez de abortar. */
async function fetchTagNames(language: string): Promise<Map<number, string>> {
  const res = await withRetries('steam GetTagList', () =>
    fetchJson<{ response?: { tags?: { tagid: number; name: string }[] } }>(
      `${API_URL}/IStoreService/GetTagList/v1/?language=${language}`,
    ),
  );
  return new Map((res?.response?.tags ?? []).map((t) => [t.tagid, t.name]));
}

function toDeal(item: StoreItem, region: string, tagNames: Map<number, string>): Deal | null {
  const price = item.best_purchase_option;
  const discountPercent = price?.discount_pct ?? 0;
  if (item.success !== 1 || !item.appid || !item.name || !price || discountPercent <= 0) return null;
  if (price.price_cannot_be_displayed_as_discount) return null;

  const storeUrl = `${STORE_URL}/${item.store_url_path ?? `app/${item.appid}`}/`;
  return {
    // Prefijo para no chocar con los IDs de otras tiendas en la misma colección.
    id: `steam-${item.appid}`,
    title: item.name.trim(),
    originalPrice: centsToNumber(price.original_price_in_cents),
    discountedPrice: centsToNumber(price.final_price_in_cents),
    originalPriceText: price.formatted_original_price ?? '',
    discountedPriceText: price.formatted_final_price ?? '',
    discountPercent,
    imageUrl: pickImage(item.assets),
    productUrl: storeUrl,
    platform: 'steam',
    storeUrl,
    region,
    // Las etiquetas vienen ordenadas por peso (votos): las primeras describen mejor el juego.
    genres: (item.tags ?? [])
      .filter((t) => GENRE_TAG_IDS.has(t.tagid) && tagNames.has(t.tagid))
      .slice(0, MAX_GENRES)
      .map((t) => tagNames.get(t.tagid)!),
    endDate: toEndDate(price.active_discounts?.map((d) => d.discount_end_date)),
  };
}

/** El fin más próximo entre los descuentos activos (segundos → ms); null si Steam no lo informa. */
function toEndDate(endsInSeconds: (number | undefined)[] | undefined): number | null {
  const valid = (endsInSeconds ?? []).filter((s): s is number => typeof s === 'number' && s > 0);
  return valid.length > 0 ? Math.min(...valid) * 1000 : null;
}

function centsToNumber(cents: string | undefined): number | null {
  const value = Number(cents);
  return cents && Number.isFinite(value) ? value / 100 : null;
}

function pickImage(assets: StoreItem['assets']): string | null {
  const format = assets?.asset_url_format;
  if (!format) return null;
  const file = IMAGE_ASSETS.map((key) => assets?.[key]).find(Boolean);
  return file ? ASSETS_URL + format.replace('${FILENAME}', file) : null;
}

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0 (PSDeals scraper)' },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} en ${new URL(url).pathname}`);
  try {
    return (await res.json()) as T;
  } catch (err) {
    throw new Error(`respuesta no JSON en ${new URL(url).pathname}: ${errMsg(err)}`);
  }
}
