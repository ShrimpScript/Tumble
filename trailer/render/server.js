// Static file server for the renderer page: maps URL prefixes to local directories.
const http = require('http');
const fs = require('fs');
const path = require('path');

const WORK = process.env.TRAILER_WORK || '/home/user/work';
const HERE = __dirname;
const routes = [
  ['/three/', path.join(WORK, 'render/node_modules/three/')],
  ['/scene/', path.join(WORK, 'scene/')],
  ['/sim/', path.join(WORK, 'sim/')],
  ['/assets/', path.join(WORK, 'assets/assets/minecraft/')],
  ['/ui/', path.join(WORK, 'ui/')],
  ['/js/', path.join(HERE, 'js/')],
  ['/', HERE + '/'],
];
const types = { '.js': 'text/javascript', '.html': 'text/html', '.json': 'application/json', '.png': 'image/png', '.ttf': 'font/ttf' };

function start(port, onFrame) {
  const srv = http.createServer((req, res) => {
    const url = decodeURIComponent(req.url.split('?')[0]);
    if (req.method === 'POST' && url.startsWith('/frame/') && onFrame) {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => { onFrame(url.slice(7), Buffer.concat(chunks)); res.end('ok'); });
      return;
    }
    for (const [prefix, dir] of routes) {
      if (url.startsWith(prefix)) {
        const file = path.join(dir, url.slice(prefix.length) || 'index.html');
        fs.readFile(file, (err, data) => {
          if (err) { res.writeHead(404); res.end(); return; }
          res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
          res.end(data);
        });
        return;
      }
    }
    res.writeHead(404); res.end();
  });
  return new Promise((r) => srv.listen(port, () => r(srv)));
}
module.exports = { start };
