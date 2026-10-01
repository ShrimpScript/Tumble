// Renders stills from a list of camera setups: node scout.js shots.json outdir
const { chromium } = require(process.env.PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const fs = require('fs');
const { start } = require('./server');

(async () => {
  const shots = JSON.parse(fs.readFileSync(process.argv[2]));
  const out = process.argv[3];
  fs.mkdirSync(out, { recursive: true });
  const W = +(process.env.W || 1280), H = +(process.env.H || 720);
  const srv = await start(8124);
  const browser = await chromium.launch({ headless: false, args: ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  page.on('console', (m) => console.log('[page]', m.text().slice(0, 300)));
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  await page.goto(`http://localhost:8124/index.html?w=${W}&h=${H}`);
  await page.waitForFunction('window.boot !== undefined');
  await page.evaluate('window.boot()');
  if (process.env.SETUP) await page.evaluate(process.env.SETUP);
  for (const [name, shot] of Object.entries(shots)) {
    const ms = await page.evaluate((s) => window.scout(s), shot);
    await page.screenshot({ path: `${out}/${name}.png` });
    console.log(name, ms.toFixed(0), 'ms');
  }
  await browser.close();
  srv.close();
})();
