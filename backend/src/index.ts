import { PUSH_MIN_DISCOUNT, saveDeals } from './firestore.js';
import { notifyDeals } from './notify.js';
import { errMsg, scrapeDeals } from './scraper.js';
import { scrapeEpicDeals } from './epic.js';
import { scrapeSteamDeals } from './steam.js';
import { scrapeXboxDeals } from './xbox.js';
import type { Deal, Platform } from './types.js';

/** Un motor por tienda; todos reciben la región de la PS Store (ej. es-co) y la adaptan a su API. */
const ENGINES: { platform: Platform; scrape: (region: string) => Promise<Deal[]> }[] = [
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
  let brokenEngines = 0;
  results.forEach((result, i) => {
    const { platform } = engines[i];
    if (result.status === 'rejected') {
      brokenEngines++;
      console.error(`[main] ${platform}: error fatal: ${errMsg(result.reason)}`);
    } else if (result.value.length === 0) {
      // Casi seguro el scraping se rompió (la tienda cambió o hubo bloqueo): no tocar sus documentos.
      brokenEngines++;
      console.error(`[main] ${platform}: 0 ofertas, no se escribirá nada de esta tienda`);
    } else {
      console.log(`[main] ${platform}: ${result.value.length} ofertas con descuento`);
      deals.push(...result.value);
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
    return brokenEngines > 0 ? 1 : 0;
  }

  const toNotify = await saveDeals(deals);
  console.log(`[main] ${toNotify.length} ofertas nuevas con >= ${PUSH_MIN_DISCOUNT}%`);
  const { failed } = await notifyDeals(toNotify);
  // Un motor roto marca la ejecución como fallida para que se note en GitHub Actions.
  return failed > 0 || brokenEngines > 0 ? 1 : 0;
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((err) => {
    console.error(`[main] error fatal: ${errMsg(err)}`);
    process.exitCode = 1;
  });
