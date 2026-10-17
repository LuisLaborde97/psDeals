import { getMessaging, type Message } from 'firebase-admin/messaging';
import { initFirebase } from './firestore.js';
import { errMsg } from './scraper.js';
import type { Deal } from './types.js';

export const TOPIC = 'daily_deals';
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
  const remaining = sorted.length - individual.length;

  const messages: Message[] = individual.map(dealMessage);
  if (remaining > 0) messages.push(summaryMessage(remaining, sorted[0].region));

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
  const title = `-${deal.discountPercent}% · ${deal.title}`;
  const body = `${deal.discountedPriceText} (antes ${deal.originalPriceText})`;
  return {
    topic: TOPIC,
    notification: { title, body, ...(deal.imageUrl && { imageUrl: deal.imageUrl }) },
    // FCM exige que todos los valores de `data` sean strings.
    data: {
      type: 'deal',
      dealId: deal.id,
      productUrl: deal.productUrl,
      discountPercent: String(deal.discountPercent),
    },
    android: {
      priority: 'high',
      notification: { channelId: ANDROID_CHANNEL_ID, tag: deal.id },
    },
  };
}

function summaryMessage(count: number, region: string): Message {
  return {
    topic: TOPIC,
    notification: {
      title: `¡${count} ofertas más de 50% o más!`,
      body: 'Abre la app para verlas todas.',
    },
    data: { type: 'summary', count: String(count), region },
    android: {
      priority: 'high',
      notification: { channelId: ANDROID_CHANNEL_ID, tag: 'summary' },
    },
  };
}
