import { PUSH_MIN_DISCOUNT, deactivateMissing, loadPreviousDeals, markPlatformsRun, saveDeals, saveGenres } from './firestore.js';
import { STORE_PUSH_MIN_DISCOUNT, notifyDeals, notifyStoreTopics, storeTopicMessages } from './notify.js';
import { errMsg, scrapeDeals } from './scraper.js';
import { scrapeEpicDeals } from './epic.js';
import { scrapeSteamDeals } from './steam.js';
import { findWishlistAlerts, sendWishlistAlerts } from './wishlistAlerts.js';
import { scrapeXboxDeals } from './xbox.js';
import type { Deal, Platform, ScrapeResult } from './types.js';

/** Un motor por tienda; todos reciben la región de la PS Store (ej. es-co) y la adaptan a su API. */
const ENGINES: { platform: Platform; scrape: (region: string) => Promise<ScrapeResult> }[] = [
  { platform: 'playstation', scrape: scrapeDeals },
  { platform: 'steam', scrape: scrapeSteamDeals },
  { platform: 'epic', scrape: scrapeEpicDeals },
  { platform: 'xbox', scrape: scrapeXboxDeals },
];

async function main(): Promise<number> {
  const region = (process.env.PS_STORE_REGION || 'es-co').toLowerCase();
  const dryRun = process.argv.includes('--dry-run');
  // --platform=steam ejecuta un solo motor (útil para probar sin esperar al de PlayStation).
  const only = process.argv.find((a) => a.startsWith('--platform='))?.split('=')[1];
  const engines = ENGINES.filter((e) => !only || e.platform === only);
  if (engines.length === 0) {
    console.error(`[main] plataforma desconocida: ${only}`);
    return 1;
  }
  console.log(`[main] región=${region} dryRun=${dryRun} motores=${engines.map((e) => e.platform).join(',')}`);

  // En paralelo: un motor que falla o vuelve vacío no impide guardar lo de los demás.
  const results = await Promise.allSettled(engines.map((e) => e.scrape(region)));
  const deals: Deal[] = [];
  /** Tiendas recorridas enteras: solo de ellas una oferta ausente significa que terminó (ver markPlatformsRun). */
  const completePlatforms: Platform[] = [];
  let brokenEngines = 0;
  results.forEach((result, i) => {
    const { platform } = engines[i];
    if (result.status === 'rejected') {
      brokenEngines++;
      console.error(`[main] ${platform}: error fatal: ${errMsg(result.reason)}`);
    } else if (result.value.deals.length === 0) {
      // Casi seguro el scraping se rompió (la tienda cambió o hubo bloqueo): no tocar sus documentos.
      brokenEngines++;
      console.error(`[main] ${platform}: 0 ofertas, no se escribirá nada de esta tienda`);
    } else {
      const { deals: found, complete } = result.value;
      const withEnd = found.filter((d) => d.endDate !== null).length;
      console.log(
        `[main] ${platform}: ${found.length} ofertas con descuento (${withEnd} con fecha de fin)` +
          (complete ? '' : ' — listado INCOMPLETO: no se marcará como ejecución completa'),
      );
      deals.push(...found);
      if (complete) completePlatforms.push(platform);
    }
  });
  if (deals.length === 0) {
    console.error('[main] ningún motor devolvió ofertas: se aborta sin escribir en Firestore');
    return 1;
  }

  if (dryRun) {
    for (const { platform } of engines) {
      const top = deals
        .filter((d) => d.platform === platform)
        .sort((a, b) => b.discountPercent - a.discountPercent);
      if (top.length === 0) continue;
      console.log(`\n[dry-run] ${platform}: top ${Math.min(25, top.length)} de ${top.length}`);
      console.table(
        top.slice(0, 25).map((d) => ({
          id: d.id,
          título: d.title.slice(0, 45),
          antes: d.originalPriceText,
          ahora: d.discountedPriceText,
          '%': d.discountPercent,
          géneros: d.genres.join(', ').slice(0, 40),
        })),
      );
      const withGenres = top.filter((d) => d.genres.length > 0).length;
      console.log(`[dry-run] ${platform}: ${withGenres}/${top.length} ofertas con al menos un género.`);
    }
    const candidates = deals.filter((d) => d.discountPercent >= PUSH_MIN_DISCOUNT).length;
    console.log(
      `[dry-run] Se escribirían ${deals.length} documentos en "deals". ` +
        `${candidates} tienen >= ${PUSH_MIN_DISCOUNT}% y recibirían push si son nuevos en Firestore.`,
    );
    // Solo lectura: detecta las alertas de wishlist y FCM valida los mensajes sin entregarlos.
    try {
      const previous = await loadPreviousDeals(deals);
      const alerts = findWishlistAlerts(deals, previous);
      printAlerts(alerts);
      await sendWishlistAlerts(alerts, { dryRun: true });
      const fresh = deals.filter((d) => !previous.has(d.id) && d.discountPercent >= STORE_PUSH_MIN_DISCOUNT);
      printStorePushes(fresh);
      await notifyStoreTopics(fresh, { dryRun: true });
    } catch (err) {
      console.warn(`[dry-run] no se pudieron revisar las alertas de wishlist (¿credenciales?): ${errMsg(err)}`);
    }
    return brokenEngines > 0 ? 1 : 0;
  }

  // El estado previo se lee una vez y antes de escribir: después ya no quedaría el precio anterior.
  const previous = await loadPreviousDeals(deals);
  const alerts = findWishlistAlerts(deals, previous);
  printAlerts(alerts);
  const { newPushCandidates, savedIds } = await saveDeals(deals, previous);
  console.log(`[main] ${newPushCandidates.length} ofertas nuevas con >= ${STORE_PUSH_MIN_DISCOUNT}%`);
  // Canal general para las versiones anteriores (mismo umbral de siempre) y canales por tienda para las nuevas.
  const legacy = await notifyDeals(newPushCandidates.filter((d) => d.discountPercent >= PUSH_MIN_DISCOUNT));
  printStorePushes(newPushCandidates);
  const byStore = await notifyStoreTopics(newPushCandidates);
  const failed = legacy.failed + byStore.failed;
  // Tokens fallidos (teléfonos desinstalados) son normales y se limpian: no marcan la ejecución como fallida.
  await sendWishlistAlerts(alerts.filter((a) => savedIds.has(a.deal.id)));
  // Solo tiendas completas cuyas ofertas se guardaron todas: con un lote fallido, sus ofertas no
  // escritas conservarían el lastSeenAt anterior y la app las tomaría por terminadas.
  const fullySaved = completePlatforms.filter((p) => deals.every((d) => d.platform !== p || savedIds.has(d.id)));
  try {
    await markPlatformsRun(fullySaved);
  } catch (err) {
    // Sin la marca la app sigue con la regla de 3 días: peor, pero no rompe nada.
    console.error(`[main] no se pudo actualizar config/scraper: ${errMsg(err)}`);
  }
  try {
    await deactivateMissing(fullySaved, savedIds);
  } catch (err) {
    // La app también las descarta por lastRunAt: solo se pierde eficiencia en la paginación.
    console.error(`[main] no se pudieron marcar las ofertas terminadas: ${errMsg(err)}`);
  }
  try {
    await saveGenres(deals);
  } catch (err) {
    console.error(`[main] no se pudo actualizar config/genres: ${errMsg(err)}`);
  }
  // Un motor roto marca la ejecución como fallida para que se note en GitHub Actions.
  return failed > 0 || brokenEngines > 0 ? 1 : 0;
}

/** Qué recibiría cada canal por tienda: tema (o condición) y título. */
function printStorePushes(deals: Parameters<typeof storeTopicMessages>[0]): void {
  const messages = storeTopicMessages(deals);
  console.log(`[notify] ${messages.length} pushes por tienda planeados para ${deals.length} ofertas nuevas`);
  if (messages.length > 0) {
    console.table(
      messages.slice(0, 30).map((m) => ({
        destino: 'topic' in m ? m.topic : 'condition' in m ? m.condition : '',
        título: m.notification?.title?.slice(0, 60),
      })),
    );
  }
}

function printAlerts(alerts: ReturnType<typeof findWishlistAlerts>): void {
  const drops = alerts.filter((a) => a.reason === 'price_drop').length;
  console.log(`[alerts] ${alerts.length} candidatas a alerta de wishlist (${drops} rebajas, ${alerts.length - drops} ofertas que volvieron)`);
  if (alerts.length > 0) {
    console.table(
      alerts.slice(0, 15).map((a) => ({
        id: a.deal.id,
        título: a.deal.title.slice(0, 40),
        motivo: a.reason,
        antes: a.previousPriceText,
        ahora: a.deal.discountedPriceText,
      })),
    );
  }
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((err) => {
    console.error(`[main] error fatal: ${errMsg(err)}`);
    process.exitCode = 1;
  });
