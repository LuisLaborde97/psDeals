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

/**
 * Inserta o actualiza las ofertas en `deals` y devuelve solo las que deben notificarse:
 * documentos que NO existían antes de esta ejecución y con descuento >= 50 %.
 * Una oferta nueva cuyo lote falla al escribirse no se devuelve, así la siguiente
 * ejecución la vuelve a ver como nueva y no se notifica algo que no quedó guardado.
 */
export async function saveDeals(deals: Deal[]): Promise<Deal[]> {
  initFirebase();
  const db = getFirestore();
  const refs = deals.map((d) => db.collection(COLLECTION).doc(d.id));
  const existing = await fetchExistingIds(db, refs);

  const pushCandidates: Deal[] = [];
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
    } catch (err) {
      failed += chunk.length;
      console.error(`[firestore] lote ${i / MAX_BATCH_OPS + 1} falló (${chunk.length} docs): ${errMsg(err)}`);
    }
  }

  console.log(
    `[firestore] ${written} escritos (${deals.length - existing.size} nuevos, ${existing.size} actualizados), ${failed} fallidos`,
  );
  return pushCandidates;
}

async function fetchExistingIds(db: Firestore, refs: DocumentReference[]): Promise<Set<string>> {
  const ids = new Set<string>();
  for (let i = 0; i < refs.length; i += READ_CHUNK) {
    const snaps = await db.getAll(...refs.slice(i, i + READ_CHUNK), { fieldMask: [] });
    for (const snap of snaps) if (snap.exists) ids.add(snap.id);
  }
  return ids;
}
