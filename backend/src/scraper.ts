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

interface CategoryPage {
  products: StoreProduct[];
  isLast: boolean;
  totalCount: number;
}

export async function scrapeDeals(region: string): Promise<Deal[]> {
  const deals = new Map<string, Deal>();
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
    for (const categoryId of categoryIds) {
      for (let pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
        const result = await withRetries(`categoría ${categoryId} p.${pageNum}`, () =>
          fetchCategoryPage(page, region, categoryId, pageNum),
        );
        if (!result) break; // agotó reintentos: seguimos con lo que ya tenemos
        addAll(result.products);
        console.log(
          `[scraper] ${categoryId} p.${pageNum}: ${result.products.length} productos (total ${result.totalCount})`,
        );
        if (result.isLast || result.products.length === 0) break;
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

  const onResponse = (res: HTTPResponse) => {
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
  return strands;
}

async function fetchCategoryPage(
  page: Page,
  region: string,
  categoryId: string,
  pageNum: number,
): Promise<CategoryPage> {
  // Se registra antes de navegar para no perder la respuesta GraphQL de la grilla.
  const gridResponse = page.waitForResponse(
    (res) => isGraphqlOp(res, 'categoryGridRetrieve') && graphqlVariables(res)?.id === categoryId,
    { timeout: NAV_TIMEOUT_MS },
  );
  // Evita un unhandled rejection si goto falla primero.
  gridResponse.catch(() => {});

  await page.goto(`${STORE_URL}/${region}/category/${categoryId}/${pageNum}`, {
    waitUntil: 'domcontentloaded',
    timeout: NAV_TIMEOUT_MS,
  });

  const res = await gridResponse;
  if (!res.ok()) throw new Error(`HTTP ${res.status()} en categoryGridRetrieve`);
  const grid = (await res.json())?.data?.categoryGridRetrieve;
  if (!grid) throw new Error('respuesta sin categoryGridRetrieve');

  return {
    products: grid.products ?? [],
    isLast: grid.pageInfo?.isLast ?? true,
    totalCount: grid.pageInfo?.totalCount ?? 0,
  };
}

function toDeal(p: StoreProduct, region: string): Deal | null {
  const price = p.price;
  if (!p.id || !p.name || !price?.discountText) return null;

  const originalPrice = parsePrice(price.basePrice);
  const discountedPrice = parsePrice(price.discountedPrice);
  const discountPercent = parsePercent(price.discountText, originalPrice, discountedPrice);
  if (discountPercent <= 0) return null;

  return {
    id: p.id,
    title: p.name.trim(),
    originalPrice,
    discountedPrice,
    originalPriceText: price.basePrice ?? '',
    discountedPriceText: price.discountedPrice ?? '',
    discountPercent,
    imageUrl: pickImage(p.media),
    productUrl: `${STORE_URL}/${region}/product/${p.id}`,
    region,
  };
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

async function withRetries<T>(label: string, fn: () => Promise<T>): Promise<T | null> {
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
