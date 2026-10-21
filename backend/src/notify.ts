import { getMessaging, type Message } from 'firebase-admin/messaging';
import { initFirebase } from './firestore.js';
import { errMsg } from './scraper.js';
import type { Deal, Platform } from './types.js';

export const TOPIC = 'daily_deals';
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

  const sorted = [...deals].sort((a, b) => b.discountPercent - a.discountPercent);
  const individual = sorted.slice(0, MAX_PUSHES);
  const remaining = sorted.slice(MAX_PUSHES);

  const messages: Message[] = individual.map(dealMessage);
  if (remaining.length > 0) messages.push(summaryMessage(remaining));

  let sent = 0;
  let failed = 0;
  for (const message of messages) {
    try {
      await messaging.send(message);
      sent++;
    } catch (err) {
      failed++;
      console.error(`[notify] envío falló: ${errMsg(err)}`);
    }
  }
  console.log(`[notify] ${sent} pushes enviados a "${TOPIC}", ${failed} fallidos`);
  return { sent, failed };
}

export function dealMessage(deal: Deal): Message {
  // La tienda va primero: con varias tiendas, el mismo juego puede llegar desde dos de ellas.
  const title = `[${PLATFORM_LABELS[deal.platform]}] -${deal.discountPercent}% · ${deal.title}`;
  const body = `${deal.discountedPriceText} (antes ${deal.originalPriceText})`;
  return {
    topic: TOPIC,
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

/** ["Steam", "Epic Games", "PlayStation"] → "Steam, Epic Games y PlayStation". */
function joinSpanish(items: string[]): string {
  return items.length <= 1 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} y ${items.at(-1)}`;
}
