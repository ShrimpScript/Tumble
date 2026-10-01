// Prints frames whose camera is inside or touching terrain: node audit.js FROM TO
const { chromium } = require(process.env.PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const { start } = require('./server');
(async () => {
  const srv = await start(8199);
  const browser = await chromium.launch({ headless: false, args: ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
  page.on('console', (m) => { const t = m.text(); if (t.includes('camsolve')) console.log('[page]', t); });
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  await page.goto('http://localhost:8199/index.html?w=640&h=360');
  await page.waitForFunction('window.boot !== undefined');
  await page.evaluate('window.boot()');
  const bad = await page.evaluate(([a, b]) => window.audit(a, b, 2), [+process.argv[2], +process.argv[3]]);
  console.log(bad.length ? bad.map((x) => JSON.stringify(x)).join('\n') : 'camera clear on all sampled frames');
  await browser.close(); srv.close();
})();
