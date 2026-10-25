import { fileURLToPath } from 'node:url';
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, type DocumentReference, type Firestore } from 'firebase-admin/firestore';
import { configDocId, dealsCollection } from './country.js';
import { errMsg } from './scraper.js';
import { STORE_PUSH_MIN_DISCOUNT } from './notify.js';
import type { Deal, Platform } from './types.js';

const MAX_BATCH_OPS = 450;
const READ_CHUNK = 300;
/** Prefijos por palabra para la búsqueda de la app; tope de largo (la app recorta igual su término). */
const MAX_TOKEN_LENGTH = 15;
/** Umbral del canal general de las versiones anteriores (notify.ts TOPIC). */
export const PUSH_MIN_DISCOUNT = 50;

/** backend/serviceAccountKey.json, válido tanto desde src/ (tsx) como desde dist/ (node). */
const DEFAULT_KEY_PATH = fileURLToPath(new URL('../serviceAccountKey.json', import.meta.url));

export function initFirebase(): void {
  if (getApps().length > 0) return;
  // Solo se pasa la ruta; el SDK lee el archivo. Nunca se carga ni se registra su contenido aquí.
  initializeApp({ credential: cert(process.env.GOOGLE_APPLICATION_CREDENTIALS || DEFAULT_KEY_PATH) });
}

/** Lo que había en Firestore antes de esta ejecución; es la base para detectar rebajas. */
export interface PreviousDeal {
  discountedPrice: number | null;
  discountedPriceText: string;
  /** Epoch millis; null en documentos sin el campo. */
  lastSeenAt: number | null;
}

export interface SaveResult {
  /** Ofertas nuevas con >= STORE_PUSH_MIN_DISCOUNT % (cada canal de push filtra después por su umbral). */
  newPushCandidates: Deal[];
  /** IDs cuyo lote se escribió: solo de esas se avisa, para no notificar algo que no quedó guardado. */
  savedIds: Set<string>;
}

/** Estado previo de las ofertas, en una sola pasada y leyendo solo los campos que hacen falta. */
export async function loadPreviousDeals(deals: Deal[], country: string): Promise<Map<string, PreviousDeal>> {
  initFirebase();
  const db = getFirestore();
  const refs = deals.map((d) => db.collection(dealsCollection(country)).doc(d.id));
  return fetchPrevious(db, refs);
}

/**
 * Inserta o actualiza las ofertas en `deals`. Devuelve las nuevas que merecen push general
 * (documentos que NO existían, con descuento >= 50 %) y qué IDs se guardaron.
 * Una oferta nueva cuyo lote falla al escribirse no se devuelve, así la siguiente
 * ejecución la vuelve a ver como nueva y no se notifica algo que no quedó guardado.
 *
 * @param previous estado previo ya leído (index.ts lo necesita antes para detectar rebajas).
 */
export async function saveDeals(deals: Deal[], country: string, previous?: Map<string, PreviousDeal>): Promise<SaveResult> {
  initFirebase();
  const db = getFirestore();
  const existing = previous ?? (await loadPreviousDeals(deals, country));

  const pushCandidates: Deal[] = [];
  const savedIds = new Set<string>();
  let written = 0;
  let failed = 0;

  for (let i = 0; i < deals.length; i += MAX_BATCH_OPS) {
    const chunk = deals.slice(i, i + MAX_BATCH_OPS);
    const batch = db.batch();
    const newInChunk: Deal[] = [];

    for (const deal of chunk) {
      const ref = db.collection(dealsCollection(country)).doc(deal.id);
      const now = FieldValue.serverTimestamp();
      if (existing.has(deal.id)) {
        // Ya existía: actualización silenciosa de precios.
        batch.update(ref, {
          originalPrice: deal.originalPrice,
          discountedPrice: deal.discountedPrice,
          originalPriceText: deal.originalPriceText,
          discountedPriceText: deal.discountedPriceText,
          discountPercent: deal.discountPercent,
          platform: deal.platform,
          storeUrl: deal.storeUrl,
          endDate: deal.endDate,
          explicit: deal.explicit,
          active: true,
          searchTokens: searchTokens(deal.title),
          // Si la fase de géneros falló en esta ejecución, no se borran los que ya estaban guardados.
          ...(deal.genres.length > 0 && { genres: deal.genres }),
          updatedAt: now,
          lastSeenAt: now,
        });
      } else {
        batch.set(ref, {
          ...deal,
          active: true,
          searchTokens: searchTokens(deal.title),
          firstSeenAt: now,
          updatedAt: now,
          lastSeenAt: now,
        });
        if (deal.discountPercent >= STORE_PUSH_MIN_DISCOUNT) newInChunk.push(deal);
      }
    }

    try {
      await batch.commit();
      written += chunk.length;
      pushCandidates.push(...newInChunk);
      for (const deal of chunk) savedIds.add(deal.id);
    } catch (err) {
      failed += chunk.length;
      console.error(`[firestore] lote ${i / MAX_BATCH_OPS + 1} falló (${chunk.length} docs): ${errMsg(err)}`);
    }
  }

  console.log(
    `[firestore] ${written} escritos (${deals.length - existing.size} nuevos, ${existing.size} actualizados), ${failed} fallidos`,
  );
  return { newPushCandidates: pushCandidates, savedIds };
}

/**
 * Marca en `config/scraper.lastRunAt.<tienda>` que la tienda se recorrió completa en esta ejecución. La app
 * oculta las ofertas que no se vieron en la última ejecución de su tienda: el backend nunca borra ofertas,
 * y sin esta marca una rebaja terminada seguía visible hasta 3 días.
 * Solo se marcan tiendas cuyo motor funcionó y cuyas ofertas se guardaron todas: si se marcara una con
 * lotes fallidos, sus ofertas no escritas parecerían terminadas.
 */
export async function markPlatformsRun(platforms: Platform[], country: string): Promise<void> {
  if (platforms.length === 0) return;
  initFirebase();
  const lastRunAt = Object.fromEntries(platforms.map((p) => [p, FieldValue.serverTimestamp()]));
  const docId = configDocId('scraper', country);
  await getFirestore().collection('config').doc(docId).set({ lastRunAt }, { merge: true });
  console.log(`[firestore] config/${docId}.lastRunAt actualizado: ${platforms.join(', ')}`);
}

/**
 * Marca `active: false` las ofertas de [platforms] que esta ejecución no vio. La app pagina con
 * `where('active', '==', true)`: sin esto cada página traería rebajas terminadas (el backend no borra
 * documentos, la lista de deseos los sigue mostrando). Mismo criterio que markPlatformsRun: solo
 * tiendas recorridas completas y guardadas sin lotes fallidos.
 */
export async function deactivateMissing(platforms: Platform[], savedIds: Set<string>, country: string): Promise<void> {
  if (platforms.length === 0) return;
  initFirebase();
  const db = getFirestore();
  // select() sin campos: solo hacen falta los IDs.
  const snap = await db.collection(dealsCollection(country)).where('active', '==', true).where('platform', 'in', platforms).select().get();
  const missing = snap.docs.filter((doc) => !savedIds.has(doc.id));
  for (let i = 0; i < missing.length; i += MAX_BATCH_OPS) {
    const batch = db.batch();
    for (const doc of missing.slice(i, i + MAX_BATCH_OPS)) batch.update(doc.ref, { active: false });
    await batch.commit();
  }
  console.log(`[firestore] ${missing.length} ofertas terminadas marcadas inactivas (${platforms.join(', ')})`);
}

/**
 * `config/genres.byPlatform.<tienda>`: géneros de las ofertas de esta ejecución. La app ya no descarga
 * toda la colección, así que los chips de género salen de aquí. Merge por tienda: una ejecución con
 * --platform, o una tienda sin géneros (falló esa fase o no los publica), no borra los de las demás.
 */
export async function saveGenres(deals: Deal[], country: string): Promise<void> {
  const byPlatform: Partial<Record<Platform, string[]>> = {};
  for (const deal of deals) {
    if (deal.genres.length === 0) continue;
    byPlatform[deal.platform] = [...new Set([...(byPlatform[deal.platform] ?? []), ...deal.genres])];
  }
  if (Object.keys(byPlatform).length === 0) return;
  initFirebase();
  const docId = configDocId('genres', country);
  await getFirestore().collection('config').doc(docId).set({ byPlatform }, { merge: true });
  console.log(`[firestore] config/${docId} actualizado: ${Object.keys(byPlatform).join(', ')}`);
}

/**
 * Prefijos de cada palabra del título, normalizada igual que en la app (DealsRepository.searchWords):
 * minúsculas, sin tildes ni apóstrofos. "Pokémon Scarlet" → p, po, …, pokemon, s, sc, …, scarlet.
 */
export function searchTokens(title: string): string[] {
  const words = title
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Mn}+/gu, '')
    .replace(/['’]/g, '')
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
  const tokens = new Set<string>();
  for (const word of words) {
    for (let len = 1; len <= Math.min(word.length, MAX_TOKEN_LENGTH); len++) tokens.add(word.slice(0, len));
  }
  return [...tokens];
}

async function fetchPrevious(db: Firestore, refs: DocumentReference[]): Promise<Map<string, PreviousDeal>> {
  const previous = new Map<string, PreviousDeal>();
  for (let i = 0; i < refs.length; i += READ_CHUNK) {
    const snaps = await db.getAll(...refs.slice(i, i + READ_CHUNK), {
      fieldMask: ['discountedPrice', 'discountedPriceText', 'lastSeenAt'],
    });
    for (const snap of snaps) {
      if (!snap.exists) continue;
      const price = snap.get('discountedPrice');
      previous.set(snap.id, {
        discountedPrice: typeof price === 'number' ? price : null,
        discountedPriceText: snap.get('discountedPriceText') ?? '',
        lastSeenAt: snap.get('lastSeenAt')?.toMillis?.() ?? null,
      });
    }
  }
  return previous;
}
