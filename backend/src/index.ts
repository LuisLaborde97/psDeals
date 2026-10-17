import { PUSH_MIN_DISCOUNT, saveDeals } from './firestore.js';
import { notifyDeals } from './notify.js';
import { errMsg, scrapeDeals } from './scraper.js';

async function main(): Promise<number> {
  const region = (process.env.PS_STORE_REGION || 'es-co').toLowerCase();
  const dryRun = process.argv.includes('--dry-run');
  console.log(`[main] región=${region} dryRun=${dryRun}`);

  const deals = await scrapeDeals(region);
  console.log(`[main] ${deals.length} ofertas con descuento encontradas`);
  if (deals.length === 0) {
    // Casi seguro el scraping se rompió (la tienda cambió o hubo bloqueo): no tocar Firestore.
    console.error('[main] 0 ofertas: se aborta sin escribir en Firestore');
    return 1;
  }

  if (dryRun) {
    const top = [...deals].sort((a, b) => b.discountPercent - a.discountPercent);
    console.table(
      top.slice(0, 25).map((d) => ({
        id: d.id,
        título: d.title.slice(0, 45),
        antes: d.originalPriceText,
        ahora: d.discountedPriceText,
        '%': d.discountPercent,
        portada: d.imageUrl ? 'sí' : 'no',
      })),
    );
    const candidates = deals.filter((d) => d.discountPercent >= PUSH_MIN_DISCOUNT).length;
    console.log(
      `[dry-run] Se escribirían ${deals.length} documentos en "deals". ` +
        `${candidates} tienen >= ${PUSH_MIN_DISCOUNT}% y recibirían push si son nuevos en Firestore.`,
    );
    return 0;
  }

  const toNotify = await saveDeals(deals);
  console.log(`[main] ${toNotify.length} ofertas nuevas con >= ${PUSH_MIN_DISCOUNT}%`);
  const { failed } = await notifyDeals(toNotify);
  return failed > 0 ? 1 : 0;
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((err) => {
    console.error(`[main] error fatal: ${errMsg(err)}`);
    process.exitCode = 1;
  });
