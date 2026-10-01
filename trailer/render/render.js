// Renders a range of trailer frames to PNG files.
//   node render.js --out DIR [--w 1920 --h 1080 --fps 30 --from SEC --to SEC --step N --frames a,b,c]
const { chromium } = require(process.env.PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const fs = require('fs');
const path = require('path');
const { start } = require('./server');

const args = {};
for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const W = +(args.w || 1920), H = +(args.h || 1080), FPS = +(args.fps || 30);
const out = args.out;
fs.mkdirSync(out, { recursive: true });

(async () => {
  const port = 8130 + Math.floor(Math.random() * 50);
  const srv = await start(port, (name, buf) => fs.writeFileSync(path.join(out, name), buf));
  const browser = await chromium.launch({ headless: false, args: ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: Math.min(W, 1920), height: Math.min(H, 1080) } });
  page.on('console', (m) => { const t = m.text(); if (!t.includes('favicon') && !t.includes('willReadFrequently') && !t.includes('GPU stall')) console.log('[page]', t.slice(0, 400)); });
  page.on('pageerror', (e) => console.log('[pageerror]', e.message, e.stack));
  await page.goto(`http://localhost:${port}/index.html?w=${W}&h=${H}&fps=${FPS}`);
  await page.waitForFunction('window.boot !== undefined');
  const info = await page.evaluate('window.boot()');
  console.log('boot', JSON.stringify(info));
  fs.writeFileSync(path.join(out, 'cues.json'), JSON.stringify(await page.evaluate('window.cues()'), null, 0));
  fs.writeFileSync(path.join(out, 'shots.json'), JSON.stringify(await page.evaluate('window.shotList()'), null, 1));
  let frames;
  if (args.frames) frames = args.frames.split(',').map(Number);
  else {
    const a = Math.round((+(args.from || 0)) * FPS), b = Math.round((+(args.to || info.duration)) * FPS);
    const step = +(args.step || 1);
    frames = [];
    for (let i = a; i < b; i += step) frames.push(i);
  }
  const t0 = Date.now();
  for (let k = 0; k < frames.length; k++) {
    const i = frames[k];
    const name = `f${String(i).padStart(5, '0')}.png`;
    if (args.skipExisting && fs.existsSync(path.join(out, name))) continue;
    const ms = await page.evaluate(([i, url]) => window.renderFrame(i, url), [i, `/frame/${name}`]);
    if (k % 10 === 0 || k === frames.length - 1) {
      const el = (Date.now() - t0) / 1000;
      console.log(`frame ${i} (${k + 1}/${frames.length}) ${ms.toFixed(0)} ms, elapsed ${el.toFixed(0)} s, eta ${(el / (k + 1) * (frames.length - k - 1)).toFixed(0)} s`);
    }
  }
  await browser.close();
  srv.close();
})();
