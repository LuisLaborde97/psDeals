import { fileURLToPath } from 'node:url';
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore, type DocumentReference, type Firestore } from 'firebase-admin/firestore';
import { errMsg } from './scraper.js';
import type { Deal } from './types.js';

const COLLECTION = 'deals';
const MAX_BATCH_OPS = 450;
const READ_CHUNK = 300;
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
  /** Ofertas nuevas con >= PUSH_MIN_DISCOUNT % (push al topic general). */
  newPushCandidates: Deal[];
  /** IDs cuyo lote se escribió: solo de esas se avisa, para no notificar algo que no quedó guardado. */
  savedIds: Set<string>;
}

/** Estado previo de las ofertas, en una sola pasada y leyendo solo los campos que hacen falta. */
export async function loadPreviousDeals(deals: Deal[]): Promise<Map<string, PreviousDeal>> {
  initFirebase();
  const db = getFirestore();
  const refs = deals.map((d) => db.collection(COLLECTION).doc(d.id));
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
export async function saveDeals(deals: Deal[], previous?: Map<string, PreviousDeal>): Promise<SaveResult> {
  initFirebase();
  const db = getFirestore();
  const existing = previous ?? (await loadPreviousDeals(deals));

  const pushCandidates: Deal[] = [];
  const savedIds = new Set<string>();
  let written = 0;
  let failed = 0;

  for (let i = 0; i < deals.length; i += MAX_BATCH_OPS) {
    const chunk = deals.slice(i, i + MAX_BATCH_OPS);
    const batch = db.batch();
    const newInChunk: Deal[] = [];

    for (const deal of chunk) {
      const ref = db.collection(COLLECTION).doc(deal.id);
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
          // Si la fase de géneros falló en esta ejecución, no se borran los que ya estaban guardados.
          ...(deal.genres.length > 0 && { genres: deal.genres }),
          updatedAt: now,
          lastSeenAt: now,
        });
      } else {
        batch.set(ref, { ...deal, firstSeenAt: now, updatedAt: now, lastSeenAt: now });
        if (deal.discountPercent >= PUSH_MIN_DISCOUNT) newInChunk.push(deal);
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
