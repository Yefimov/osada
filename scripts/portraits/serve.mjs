// Minimal static server for previewing the v2 portrait review pages.
//   node scripts/portraits/serve.mjs [port]
// Serves the repo root. Open http://localhost:8137/docs/portraits/soviet-review.html
import { createServer } from 'http';
import { readFile } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve, join, extname } from 'path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const PORT = Number(process.argv[2] || 8137);
const TYPES = {
  '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.css': 'text/css',
};

createServer((req, res) => {
  const path = decodeURIComponent(req.url.split('?')[0]);
  const abs = join(ROOT, path);
  readFile(abs, (err, data) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'content-type': TYPES[extname(abs)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(PORT, () => console.log(`serving ${ROOT} at http://localhost:${PORT}/`));
