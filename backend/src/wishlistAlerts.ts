import { FieldValue, getFirestore } from 'firebase-admin/firestore';
import { getMessaging, type MulticastMessage } from 'firebase-admin/messaging';
import { initFirebase, type PreviousDeal } from './firestore.js';
import { PLATFORM_LABELS } from './notify.js';
import { errMsg } from './scraper.js';
import type { Deal } from './types.js';

const USERS = 'users';
/** Máximo de valores en un `array-contains-any` de Firestore. */
const MAX_ARRAY_CONTAINS_ANY = 30;
/** Máximo de tokens por llamada a sendEachForMulticast. */
const MAX_MULTICAST_TOKENS = 500;
const MAX_BATCH_OPS = 450;
/** Mismo umbral que la app (DealsRepository.STALE_AFTER_MS): sin verla 3 días, la oferta había terminado. */
const BACK_ON_SALE_AFTER_MS = 3 * 24 * 60 * 60 * 1000;
/** Canal que ya crea la app; uno propio para estas alertas permitiría silenciarlas por separado. */
const ANDROID_CHANNEL_ID = 'daily_deals';
/**
 * Errores que significan que el token ya no sirve (app desinstalada, datos borrados, token rotado).
 * `invalid-argument` NO está: también lo produce un payload mal formado, y borraría tokens válidos.
 */
const DEAD_TOKEN_ERRORS = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

export interface WishlistAlert {
  deal: Deal;
  reason: 'price_drop' | 'back_on_sale';
  /** Precio que tenía en Firestore antes de esta ejecución (para "bajó de X a Y"). */
  previousPriceText: string;
}

export interface AlertsReport {
  alerts: number;
  /** Alertas con al menos un destinatario. */
  sentAlerts: number;
  tokensTargeted: number;
  delivered: number;
  failed: number;
  deadTokensRemoved: number;
}

/**
 * Ofertas por las que avisar a quien las tenga en su wishlist: bajó el precio respecto a la ejecución
 * anterior, o la oferta había terminado (no se vio en días) y volvió. Las ofertas nuevas no cuentan:
 * si no existían en Firestore, nadie pudo guardarlas.
 */
export function findWishlistAlerts(
  deals: Deal[],
  previous: Map<string, PreviousDeal>,
  now: number = Date.now(),
): WishlistAlert[] {
  const alerts: WishlistAlert[] = [];
  for (const deal of deals) {
    const before = previous.get(deal.id);
    if (!before) continue;
    if (before.lastSeenAt !== null && now - before.lastSeenAt > BACK_ON_SALE_AFTER_MS) {
      alerts.push({ deal, reason: 'back_on_sale', previousPriceText: before.discountedPriceText });
    } else if (isPriceDrop(before, deal)) {
      alerts.push({ deal, reason: 'price_drop', previousPriceText: before.discountedPriceText });
    }
  }
  return alerts;
}

/**
 * Solo compara precios en la misma moneda: Epic identifica por título, y una oferta puede pasar de la
 * colección oficial (COP) a ITAD (USD); "COP 26.999" → "USD 1,99" no es una rebaja. Bajar a 0 sí lo es
 * siempre ("Gratis" no trae moneda).
 */
function isPriceDrop(before: PreviousDeal, deal: Deal): boolean {
  const oldPrice = before.discountedPrice;
  const newPrice = deal.discountedPrice;
  if (oldPrice === null || newPrice === null || newPrice >= oldPrice) return false;
  if (newPrice === 0) return true;
  return currencyOf(before.discountedPriceText) === currencyOf(deal.discountedPriceText);
}

/** "COP 59.700" → "COP", "US$29.99" → "US$", "COL$ 199.000,00" → "COL$". */
function currencyOf(priceText: string): string {
  return priceText.replace(/[\d.,\s]/g, '');
}

/**
 * Busca quién tiene cada oferta en su wishlist, envía una notificación multicast por oferta y quita
 * de los perfiles los tokens que FCM da por muertos.
 *
 * @param dryRun FCM valida los mensajes sin entregarlos y no se escribe nada en Firestore.
 */
export async function sendWishlistAlerts(
  alerts: WishlistAlert[],
  { dryRun = false }: { dryRun?: boolean } = {},
): Promise<AlertsReport> {
  const report: AlertsReport = { alerts: alerts.length, sentAlerts: 0, tokensTargeted: 0, delivered: 0, failed: 0, deadTokensRemoved: 0 };
  if (alerts.length === 0) return report;
  initFirebase();

  const { tokensByDeal, ownersByToken } = await collectRecipients(alerts.map((a) => a.deal.id));
  const deadTokens = new Set<string>();

  for (const alert of alerts) {
    const tokens = [...(tokensByDeal.get(alert.deal.id) ?? [])];
    if (tokens.length === 0) continue;
    report.sentAlerts++;
    report.tokensTargeted += tokens.length;

    for (let i = 0; i < tokens.length; i += MAX_MULTICAST_TOKENS) {
      const chunk = tokens.slice(i, i + MAX_MULTICAST_TOKENS);
      try {
        const res = await getMessaging().sendEachForMulticast({ ...alertMessage(alert), tokens: chunk }, dryRun);
        report.delivered += res.successCount;
        report.failed += res.failureCount;
        // responses viene en el mismo orden que tokens.
        res.responses.forEach((r, idx) => {
          if (!r.success && r.error && DEAD_TOKEN_ERRORS.has(r.error.code)) deadTokens.add(chunk[idx]);
        });
      } catch (err) {
        // Falla la llamada entera (red, credenciales): se cuentan como fallidos y se sigue con el resto.
        report.failed += chunk.length;
        console.error(`[alerts] envío de "${alert.deal.title}" falló: ${errMsg(err)}`);
      }
    }
  }

  if (deadTokens.size > 0) {
    report.deadTokensRemoved = dryRun ? deadTokens.size : await removeDeadTokens(deadTokens, ownersByToken);
  }
  console.log(
    `[alerts] ${report.sentAlerts}/${report.alerts} alertas con destinatarios, ${report.delivered} entregadas, ` +
      `${report.failed} fallidas, ${report.deadTokensRemoved} tokens muertos ${dryRun ? 'detectados (dry-run, sin borrar)' : 'eliminados'}`,
  );
  return report;
}

/**
 * Usuarios con alguna de las ofertas en su wishlist, con `array-contains-any` en lotes de 30: una
 * consulta por lote y no por oferta. Los tokens se deduplican por oferta (un usuario con dos
 * teléfonos recibe en ambos; dos perfiles con el mismo token, una sola vez).
 */
async function collectRecipients(dealIds: string[]) {
  const db = getFirestore();
  const wanted = new Set(dealIds);
  const tokensByDeal = new Map<string, Set<string>>();
  const ownersByToken = new Map<string, Set<string>>();

  for (let i = 0; i < dealIds.length; i += MAX_ARRAY_CONTAINS_ANY) {
    const snap = await db
      .collection(USERS)
      .where('wishlist', 'array-contains-any', dealIds.slice(i, i + MAX_ARRAY_CONTAINS_ANY))
      .select('wishlist', 'fcmTokens')
      .get();
    for (const doc of snap.docs) {
      const tokens: string[] = (doc.get('fcmTokens') ?? []).filter((t: unknown) => typeof t === 'string' && t);
      if (tokens.length === 0) continue;
      for (const token of tokens) {
        if (!ownersByToken.has(token)) ownersByToken.set(token, new Set());
        ownersByToken.get(token)!.add(doc.id);
      }
      for (const dealId of doc.get('wishlist') ?? []) {
        if (!wanted.has(dealId)) continue;
        if (!tokensByDeal.has(dealId)) tokensByDeal.set(dealId, new Set());
        for (const token of tokens) tokensByDeal.get(dealId)!.add(token);
      }
    }
  }
  return { tokensByDeal, ownersByToken };
}

/** Un mismo token puede estar en varios perfiles (cuentas que se usaron en el mismo teléfono). */
async function removeDeadTokens(deadTokens: Set<string>, ownersByToken: Map<string, Set<string>>): Promise<number> {
  const db = getFirestore();
  const byUser = new Map<string, string[]>();
  for (const token of deadTokens) {
    for (const uid of ownersByToken.get(token) ?? []) {
      if (!byUser.has(uid)) byUser.set(uid, []);
      byUser.get(uid)!.push(token);
    }
  }

  const entries = [...byUser];
  for (let i = 0; i < entries.length; i += MAX_BATCH_OPS) {
    const batch = db.batch();
    for (const [uid, tokens] of entries.slice(i, i + MAX_BATCH_OPS)) {
      batch.update(db.collection(USERS).doc(uid), { fcmTokens: FieldValue.arrayRemove(...tokens) });
    }
    try {
      await batch.commit();
    } catch (err) {
      // No es grave: la próxima ejecución los vuelve a detectar.
      console.error(`[alerts] limpieza de tokens falló: ${errMsg(err)}`);
    }
  }
  return deadTokens.size;
}

/**
 * Notificación visible (la muestra el sistema con la app cerrada) + data para la app.
 * Mismo tag por oferta: si llega otra alerta del mismo juego, reemplaza a la anterior en vez de apilarse.
 */
function alertMessage({ deal, reason, previousPriceText }: WishlistAlert): Omit<MulticastMessage, 'tokens'> {
  const store = PLATFORM_LABELS[deal.platform];
  const title = reason === 'price_drop' ? '¡Bajó de precio! 💸' : '¡Volvió la oferta! 🔥';
  const price = deal.discountPercent >= 100 ? 'gratis' : deal.discountedPriceText;
  const body =
    reason === 'price_drop'
      ? `${deal.title} bajó de ${previousPriceText} a ${price} en ${store} (-${deal.discountPercent}%)`
      : `${deal.title} vuelve a estar en oferta en ${store}: ${price} (-${deal.discountPercent}%)`;
  return {
    notification: { title, body, ...(deal.imageUrl && { imageUrl: deal.imageUrl }) },
    // FCM exige strings en data.
    data: {
      type: 'wishlist_alert',
      reason,
      dealId: deal.id,
      platform: deal.platform,
      storeUrl: deal.storeUrl,
      productUrl: deal.productUrl,
      discountPercent: String(deal.discountPercent),
    },
    android: {
      priority: 'high',
      notification: { channelId: ANDROID_CHANNEL_ID, tag: `wishlist-${deal.id}` },
    },
  };
}
