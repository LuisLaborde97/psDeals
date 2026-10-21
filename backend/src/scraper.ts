import puppeteer, { type Browser, type HTTPResponse, type Page } from 'puppeteer';
import type { Deal } from './types.js';

const STORE_URL = 'https://store.playstation.com';
/** Categoría "Todas las ofertas" (GMA_ALL_DEALS). Es la misma en es-co y en-us. */
const ALL_DEALS_CATEGORY_ID = '3f772501-f6f8-49b7-abac-874a88ca4897';
const NAV_TIMEOUT_MS = 45_000;
const MAX_RETRIES = 3;
/** Tope de seguridad; el recorrido termina antes con pageInfo.isLast (es-co ≈ 32 págs., en-us ≈ 215). */
const MAX_PAGES = Number(process.env.MAX_PAGES ?? 250);
/** Orden de preferencia para la portada; MASTER es la carátula cuadrada. */
const IMAGE_ROLES = ['MASTER', 'GAMEHUB_COVER_ART', 'PORTRAIT_BANNER', 'EDITION_KEY_ART'];
/**
 * Los productos de la grilla no traen géneros: la API solo los expone como faceta de filtro.
 * Por eso se recorre la categoría una vez por género (`?ACTION=productGenres`) y se etiqueta
 * cada oferta que aparece en ese listado.
 */
const GENRE_FACET = 'productGenres';

interface StoreProduct {
  id: string;
  name: string;
  price?: {
    basePrice?: string | null;
    discountedPrice?: string | null;
    discountText?: string | null;
  } | null;
  media?: { role: string; type: string; url: string }[] | null;
}

interface FacetValue {
  key: string;
  displayName: string;
}

interface CategoryFilter {
  facet: string;
  key: string;
}

interface CategoryPage {
  products: StoreProduct[];
  isLast: boolean;
  totalCount: number;
  genres: FacetValue[];
}

export async function scrapeDeals(region: string): Promise<Deal[]> {
  const deals = new Map<string, Deal>();
  /** Todos los productos vistos (con o sin descuento): la categoría de ofertas también lista precios completos. */
  const seenProducts = new Set<string>();
  let browser: Browser | undefined;

  try {
    browser = await puppeteer.launch({
      headless: true,
      args: process.env.CI ? ['--no-sandbox', '--disable-setuid-sandbox'] : [],
    });
    const page = await browser.newPage();
    await blockHeavyResources(page);

    const addAll = (products: StoreProduct[]) => {
      for (const p of products) {
        if (p.id) seenProducts.add(p.id);
        const deal = toDeal(p, region);
        if (deal) deals.set(deal.id, deal);
      }
    };

    // 1. Página de ofertas: carruseles (strands) + IDs de sus categorías "ver todo".
    const categoryIds = new Set<string>([ALL_DEALS_CATEGORY_ID]);
    try {
      const strands = await scrapeDealsLanding(page, region);
      for (const s of strands) {
        categoryIds.add(s.id);
        addAll(s.products);
      }
    } catch (err) {
      console.warn(`[scraper] /pages/deals no disponible en ${region}: ${errMsg(err)}`);
    }

    // 2. Recorre cada categoría de ofertas página por página.
    const genresByCategory = new Map<string, FacetValue[]>();
    for (const categoryId of categoryIds) {
      genresByCategory.set(categoryId, await crawlCategory(page, region, categoryId, null, addAll));
    }
    console.log(`[scraper] ${deals.size} con descuento de ${seenProducts.size} productos`);

    // 3. Géneros: un recorrido filtrado por cada género, etiquetando las ofertas que aparecen.
    if (deals.size > 0) {
      for (const [categoryId, genres] of genresByCategory) {
        for (const genre of genres) {
          let tagged = 0;
          await crawlCategory(page, region, categoryId, { facet: GENRE_FACET, key: genre.key }, (products) => {
            for (const p of products) {
              const deal = deals.get(p.id);
              if (deal && !deal.genres.includes(genre.displayName)) {
                deal.genres.push(genre.displayName);
                tagged++;
              }
            }
          });
          console.log(`[scraper] género "${genre.displayName}": ${tagged} ofertas`);
        }
      }
    }
  } finally {
    await browser?.close().catch(() => {});
  }

  return [...deals.values()];
}

async function scrapeDealsLanding(
  page: Page,
  region: string,
): Promise<{ id: string; products: StoreProduct[] }[]> {
  const strands: { id: string; products: StoreProduct[] }[] = [];
  const pending: Promise<void>[] = [];
  /** `views` de la experiencia "deals"; undefined si la respuesta no llegó. */
  let landingViews: unknown;

  const onResponse = (res: HTTPResponse) => {
    if (isGraphqlOp(res, 'getExperience')) {
      pending.push(
        res
          .json()
          .then((json) => {
            const experience = json?.data?.emsExperienceRetrieve;
            if (experience) landingViews = experience.views;
          })
          .catch(() => {}),
      );
      return;
    }
    if (!isGraphqlOp(res, 'categoryStrandRetrieve')) return;
    pending.push(
      res
        .json()
        .then((json) => {
          const strand = json?.data?.categoryStrandRetrieve;
          if (strand?.id) strands.push({ id: strand.id, products: strand.products ?? [] });
        })
        .catch(() => {}),
    );
  };

  page.on('response', onResponse);
  try {
    await page.goto(`${STORE_URL}/${region}/pages/deals`, {
      waitUntil: 'networkidle2',
      timeout: NAV_TIMEOUT_MS,
    });
    await Promise.all(pending);
  } finally {
    page.off('response', onResponse);
  }

  // Entre campañas Sony publica la landing sin carruseles: no es un error, pero solo queda la categoría base.
  if (landingViews === null || (Array.isArray(landingViews) && landingViews.length === 0)) {
    console.warn(`[scraper] landing /pages/deals vacía en ${region} (views: null): Sony no publica carruseles de ofertas`);
  } else if (strands.length === 0) {
    console.warn(`[scraper] landing /pages/deals sin carruseles en ${region}: solo se recorrerá "Todas las ofertas"`);
  } else {
    console.log(`[scraper] landing /pages/deals: ${strands.length} carruseles`);
  }
  return strands;
}

/** Recorre todas las páginas de una categoría (opcionalmente filtrada) y devuelve la faceta de géneros. */
async function crawlCategory(
  page: Page,
  region: string,
  categoryId: string,
  filter: CategoryFilter | null,
  onProducts: (products: StoreProduct[]) => void,
): Promise<FacetValue[]> {
  const label = filter ? `${categoryId} [${filter.key}]` : categoryId;
  let genres: FacetValue[] = [];
  for (let pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
    const result = await withRetries(`categoría ${label} p.${pageNum}`, () =>
      fetchCategoryPage(page, region, categoryId, pageNum, filter),
    );
    if (!result) break; // agotó reintentos: seguimos con lo que ya tenemos
    if (pageNum === 1) genres = result.genres;
    onProducts(result.products);
    if (!filter) {
      console.log(`[scraper] ${label} p.${pageNum}: ${result.products.length} productos (total ${result.totalCount})`);
    }
    if (result.isLast || result.products.length === 0) break;
  }
  return genres;
}

async function fetchCategoryPage(
  page: Page,
  region: string,
  categoryId: string,
  pageNum: number,
  filter: CategoryFilter | null,
): Promise<CategoryPage> {
  const expectedFilter = filter ? [`${filter.facet}:${filter.key}`] : [];
  // Se registra antes de navegar para no perder la respuesta GraphQL de la grilla. Con filtro, la
  // página dispara primero la consulta SIN filtro: hay que comparar también `filterBy`.
  const gridResponse = page.waitForResponse(
    (res) => {
      if (!isGraphqlOp(res, 'categoryGridRetrieve')) return false;
      const vars = graphqlVariables(res);
      return vars?.id === categoryId && JSON.stringify(vars.filterBy ?? []) === JSON.stringify(expectedFilter);
    },
    { timeout: NAV_TIMEOUT_MS },
  );
  // Evita un unhandled rejection si goto falla primero.
  gridResponse.catch(() => {});

  // La tienda codifica los filtros como `?VALOR=faceta` (ej. ?ACTION=productGenres). La "/" debe ir
  // literal: con `?MUSIC%2FRHYTHM=...` la tienda ignora el filtro y la respuesta filtrada nunca llega.
  const query = filter ? `?${encodeURIComponent(filter.key).replace(/%2F/gi, '/')}=${filter.facet}` : '';
  await page.goto(`${STORE_URL}/${region}/category/${categoryId}/${pageNum}${query}`, {
    waitUntil: 'domcontentloaded',
    timeout: NAV_TIMEOUT_MS,
  });

  const res = await gridResponse;
  if (!res.ok()) throw new Error(`HTTP ${res.status()} en categoryGridRetrieve`);
  const grid = (await res.json())?.data?.categoryGridRetrieve;
  if (!grid) throw new Error('respuesta sin categoryGridRetrieve');

  const genreFacet = (grid.facetOptions ?? []).find((f: { name: string }) => f.name === GENRE_FACET);
  return {
    products: grid.products ?? [],
    isLast: grid.pageInfo?.isLast ?? true,
    totalCount: grid.pageInfo?.totalCount ?? 0,
    genres: (genreFacet?.values ?? [])
      .filter((v: FacetValue) => v.key && v.displayName)
      .map((v: FacetValue) => ({ key: v.key, displayName: v.displayName })),
  };
}

function toDeal(p: StoreProduct, region: string): Deal | null {
  const price = p.price;
  if (!p.id || !p.name || !price?.discountText) return null;

  const originalPrice = parsePrice(price.basePrice);
  const discountedPrice = parsePrice(price.discountedPrice);
  const discountPercent = parsePercent(price.discountText, originalPrice, discountedPrice);
  if (discountPercent <= 0) return null;

  const storeUrl = playStationProductUrl(region, p.id);
  return {
    id: p.id,
    title: p.name.trim(),
    originalPrice,
    discountedPrice,
    originalPriceText: price.basePrice ?? '',
    discountedPriceText: price.discountedPrice ?? '',
    discountPercent,
    imageUrl: pickImage(p.media),
    // productUrl se conserva porque la app Android lo exige; storeUrl es el campo común a todas las tiendas.
    productUrl: storeUrl,
    platform: 'playstation',
    storeUrl,
    region,
    genres: [], // se completan en la fase de géneros de scrapeDeals
  };
}

export function playStationProductUrl(region: string, productId: string): string {
  return `${STORE_URL}/${region}/product/${productId}`;
}

/** "US$39.99", "$1,299.99", "1.299,99 €" → número. Null si no hay cifra (ej. "Gratis"). */
export function parsePrice(text: string | null | undefined): number | null {
  if (!text) return null;
  const raw = text.replace(/[^\d.,]/g, '');
  if (!/\d/.test(raw)) return null;
  // El último separador seguido de 1-2 dígitos al final es el decimal.
  const m = raw.match(/^(.*?)[.,](\d{1,2})$/);
  const value = m ? Number(`${m[1].replace(/[.,]/g, '')}.${m[2]}`) : Number(raw.replace(/[.,]/g, ''));
  return Number.isFinite(value) ? value : null;
}

/** Para tiendas que dan el precio como número: "COP 28.789" (formato colombiano); "Gratis" si es 0. */
export function formatPrice(amount: number, currency: string): string {
  if (amount === 0) return 'Gratis';
  return new Intl.NumberFormat('es-CO', { style: 'currency', currency, currencyDisplay: 'code' })
    .format(amount)
    .replace(/ /g, ' ');
}

function parsePercent(text: string, original: number | null, discounted: number | null): number {
  const m = text.match(/(\d{1,3})/);
  if (m) return Number(m[1]);
  if (original && discounted !== null && original > 0) {
    return Math.round((1 - discounted / original) * 100);
  }
  return 0;
}

function pickImage(media: StoreProduct['media']): string | null {
  const images = (media ?? []).filter((m) => m.type === 'IMAGE' && m.url);
  for (const role of IMAGE_ROLES) {
    const hit = images.find((m) => m.role === role);
    if (hit) return hit.url;
  }
  return images[0]?.url ?? null;
}

function isGraphqlOp(res: HTTPResponse, operationName: string): boolean {
  // Ignora el preflight CORS (OPTIONS): coincide con la URL pero no tiene cuerpo.
  if (res.request().method() !== 'GET') return false;
  const url = res.url();
  return url.includes('/api/graphql/') && url.includes(`operationName=${operationName}`);
}

function graphqlVariables(res: HTTPResponse): Record<string, unknown> | null {
  try {
    const vars = new URL(res.url()).searchParams.get('variables');
    return vars ? JSON.parse(vars) : null;
  } catch {
    return null;
  }
}

async function blockHeavyResources(page: Page): Promise<void> {
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (req.isInterceptResolutionHandled()) return;
    const type = req.resourceType();
    if (type === 'image' || type === 'media' || type === 'font') void req.abort();
    else void req.continue();
  });
}

export async function withRetries<T>(label: string, fn: () => Promise<T>): Promise<T | null> {
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      return await fn();
    } catch (err) {
      console.warn(`[scraper] ${label} falló (intento ${attempt}/${MAX_RETRIES}): ${errMsg(err)}`);
      if (attempt < MAX_RETRIES) await new Promise((r) => setTimeout(r, 2_000 * attempt));
    }
  }
  return null;
}

export function errMsg(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
