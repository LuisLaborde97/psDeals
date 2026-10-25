import { getMessaging, type Message, type Messaging } from 'firebase-admin/messaging';
import { initFirebase } from './firestore.js';
import { errMsg } from './scraper.js';
import type { Deal, Platform } from './types.js';

/**
 * Canal general de las versiones anteriores a las preferencias de notificación (beta06 y antes): recibe las
 * ofertas de todas las tiendas desde PUSH_MIN_DISCOUNT. Las versiones nuevas se salen de él y usan [storeTopic].
 */
export const TOPIC = 'daily_deals';
/**
 * Opciones de "Avisarme desde" en la app (NotificationPreferences.DISCOUNT_OPTIONS): cada teléfono se suscribe a
 * un solo tema por tienda, el de su umbral. 100 = solo juegos gratis.
 */
export const DISCOUNT_BUCKETS = [30, 50, 70, 100] as const;
export type DiscountBucket = (typeof DISCOUNT_BUCKETS)[number];
/** Las ofertas nuevas se consideran para push desde el umbral más bajo que ofrece la app. */
export const STORE_PUSH_MIN_DISCOUNT = DISCOUNT_BUCKETS[0];
/** Pushes individuales por tienda y ejecución; el resto de esa tienda va en un resumen por umbral. */
const MAX_PUSHES_PER_STORE = Number(process.env.MAX_PUSHES_PER_STORE ?? 2);

/** "deals_mx_xbox_50": ofertas de Xbox en México desde -50 %. Debe coincidir con AppPreferences.topicFor en la app. */
export function storeTopic(country: string, platform: Platform, bucket: DiscountBucket): string {
  return `deals_${country}_${platform}_${bucket}`;
}
/** Nombre visible de cada tienda en los pushes (mismos textos que los chips de la app). */
export const PLATFORM_LABELS: Record<Platform, string> = {
  playstation: 'PlayStation',
  steam: 'Steam',
  epic: 'Epic Games',
  xbox: 'Xbox',
};
/** Canal de notificaciones que creará la app Android. */
const ANDROID_CHANNEL_ID = 'daily_deals';
/**
 * Tope de pushes individuales por ejecución. Al inicio de una gran rebaja (o en la primera
 * ejecución, con la colección vacía) pueden aparecer cientos de ofertas nuevas a la vez:
 * se envían las de mayor descuento y el resto se agrupa en un único push de resumen.
 */
const MAX_PUSHES = Number(process.env.MAX_PUSHES_PER_RUN ?? 5);

export async function notifyDeals(deals: Deal[]): Promise<{ sent: number; failed: number }> {
  if (deals.length === 0) return { sent: 0, failed: 0 };
  initFirebase();
  const messaging = getMessaging();

  // Nunca contenido explícito en pushes: llegan a todos (también a la versión de Play) con su imagen.
  const sorted = deals.filter((d) => !d.explicit).sort((a, b) => b.discountPercent - a.discountPercent);
  const individual = sorted.slice(0, MAX_PUSHES);
  const remaining = sorted.slice(MAX_PUSHES);

  const messages: Message[] = individual.map((d) => dealMessage(d));
  if (remaining.length > 0) messages.push(summaryMessage(remaining));

  const result = await sendAll(messaging, messages);
  console.log(`[notify] ${result.sent} pushes enviados a "${TOPIC}", ${result.failed} fallidos`);
  return result;
}

/**
 * Pushes por tienda y umbral, para las versiones con preferencias de notificación. Por cada tienda: sus
 * [MAX_PUSHES_PER_STORE] ofertas de mayor descuento, cada una a todos los temas cuyo umbral alcanza, y por cada
 * umbral un resumen del resto. Así nadie recibe ofertas de una tienda que no eligió ni por debajo de su umbral,
 * y cada teléfono recibe a lo sumo un resumen por tienda.
 *
 * @param dryRun FCM valida los mensajes sin entregarlos.
 */
export async function notifyStoreTopics(
  deals: Deal[],
  country: string,
  { dryRun = false } = {},
): Promise<{ sent: number; failed: number }> {
  const messages = storeTopicMessages(deals, country);
  if (messages.length === 0) return { sent: 0, failed: 0 };
  initFirebase();
  const result = await sendAll(getMessaging(), messages, dryRun);
  const verb = dryRun ? 'validados (sin enviar)' : 'enviados';
  console.log(`[notify] ${result.sent} pushes por tienda ${verb}, ${result.failed} fallidos`);
  return result;
}

export function storeTopicMessages(deals: Deal[], country: string): Message[] {
  const messages: Message[] = [];
  const platforms = [...new Set(deals.map((d) => d.platform))];
  for (const platform of platforms) {
    const sorted = deals
      .filter((d) => d.platform === platform && !d.explicit && d.discountPercent >= STORE_PUSH_MIN_DISCOUNT)
      .sort((a, b) => b.discountPercent - a.discountPercent);
    for (const deal of sorted.slice(0, MAX_PUSHES_PER_STORE)) {
      // Condición y no un tema: la oferta llega una sola vez a cada teléfono aunque esté en varios umbrales.
      const condition = DISCOUNT_BUCKETS.filter((b) => deal.discountPercent >= b)
        .map((b) => `'${storeTopic(country, platform, b)}' in topics`)
        .join(' || ');
      messages.push(dealMessage(deal, { condition }));
    }
    const remaining = sorted.slice(MAX_PUSHES_PER_STORE);
    for (const bucket of DISCOUNT_BUCKETS) {
      const forBucket = remaining.filter((d) => d.discountPercent >= bucket);
      if (forBucket.length > 0) messages.push(storeSummaryMessage(country, platform, bucket, forBucket));
    }
  }
  return messages;
}

async function sendAll(messaging: Messaging, messages: Message[], dryRun = false): Promise<{ sent: number; failed: number }> {
  let sent = 0;
  let failed = 0;
  for (const message of messages) {
    try {
      await messaging.send(message, dryRun);
      sent++;
    } catch (err) {
      failed++;
      console.error(`[notify] envío falló: ${errMsg(err)}`);
    }
  }
  return { sent, failed };
}

/** @param target tema o condición de FCM; por defecto el canal general de las versiones anteriores. */
export function dealMessage(deal: Deal, target: { topic: string } | { condition: string } = { topic: TOPIC }): Message {
  // La tienda va primero: con varias tiendas, el mismo juego puede llegar desde dos de ellas.
  const title = `[${PLATFORM_LABELS[deal.platform]}] -${deal.discountPercent}% · ${deal.title}`;
  const body = `${deal.discountedPriceText} (antes ${deal.originalPriceText})`;
  return {
    ...target,
    notification: { title, body, ...(deal.imageUrl && { imageUrl: deal.imageUrl }) },
    // FCM exige que todos los valores de `data` sean strings.
    data: {
      type: 'deal',
      dealId: deal.id,
      platform: deal.platform,
      productUrl: deal.productUrl,
      storeUrl: deal.storeUrl,
      discountPercent: String(deal.discountPercent),
    },
    android: {
      priority: 'high',
      notification: { channelId: ANDROID_CHANNEL_ID, tag: deal.id },
    },
  };
}

/** Resumen de las ofertas que no tuvieron push propio, desglosado por tienda (la de más ofertas primero). */
export function summaryMessage(deals: Deal[]): Message {
  const counts = new Map<Platform, number>();
  for (const d of deals) counts.set(d.platform, (counts.get(d.platform) ?? 0) + 1);
  const platforms = [...counts].sort((a, b) => b[1] - a[1]);
  const names = platforms.map(([p]) => PLATFORM_LABELS[p]);
  // Con una sola tienda el título ya la nombra; el desglose solo aporta cuando hay varias.
  const breakdown = platforms.length > 1 ? `${platforms.map(([p, n]) => `${PLATFORM_LABELS[p]}: ${n}`).join(' · ')}. ` : '';
  return {
    topic: TOPIC,
    notification: {
      title: `¡${deals.length} ofertas más de 50% o más en ${joinSpanish(names)}!`,
      body: `${breakdown}Abre la app para verlas todas.`,
    },
    data: {
      type: 'summary',
      count: String(deals.length),
      region: deals[0].region,
      platforms: platforms.map(([p]) => p).join(','),
    },
    android: {
      priority: 'high',
      notification: { channelId: ANDROID_CHANNEL_ID, tag: 'summary' },
    },
  };
}

/** Resumen de una tienda para un umbral: solo lo reciben quienes eligieron esa tienda con ese umbral. */
function storeSummaryMessage(country: string, platform: Platform, bucket: DiscountBucket, deals: Deal[]): Message {
  const store = PLATFORM_LABELS[platform];
  const n = deals.length;
  const title = bucket === 100
    ? `¡${n} ${n === 1 ? 'juego gratis más' : 'juegos gratis más'} en ${store}!`
    : `¡${n} ${n === 1 ? 'oferta más' : 'ofertas más'} de -${bucket}% o más en ${store}!`;
  return {
    topic: storeTopic(country, platform, bucket),
    notification: { title, body: 'Abre la app para verlas todas.' },
    data: { type: 'summary', count: String(n), region: deals[0].region, platforms: platform },
    android: {
      priority: 'high',
      // Un resumen por tienda: el de otra tienda no lo reemplaza en la bandeja.
      notification: { channelId: ANDROID_CHANNEL_ID, tag: `summary-${platform}` },
    },
  };
}

/** ["Steam", "Epic Games", "PlayStation"] → "Steam, Epic Games y PlayStation". */
function joinSpanish(items: string[]): string {
  return items.length <= 1 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} y ${items.at(-1)}`;
}
