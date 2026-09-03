// Builds docs/portraits/headgear-audit.html — each v2 headgear rendered ALONE on white, black,
// skin-tone and transparent-checkerboard backgrounds, to prove every cap has a visible filled body.
//   node scripts/portraits/build-headgear-sheet.mjs

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve, join } from 'path';
import { layerPath } from '../../src/jsMain/resources/portraits/portrait-core-v2.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const RES = join(ROOT, 'src/jsMain/resources/portraits');
const manifest = JSON.parse(readFileSync(join(RES, 'v2/manifest.json'), 'utf8'));

let seq = 0;
function inline(id) {
  const px = `h${seq++}_`;
  let svg = readFileSync(join(RES, layerPath(manifest, id)), 'utf8');
  svg = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '')
    .replace(/id="([^"]+)"/g, (_, g) => `id="${px}${g}"`)
    .replace(/url\(#([^)]+)\)/g, (_, g) => `url(#${px}${g})`);
  return `<svg viewBox="0 0 300 400" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">${svg}</svg>`;
}

const backgrounds = [
  { name: 'white', css: 'background:#ffffff' },
  { name: 'black', css: 'background:#000000' },
  { name: 'skin', css: 'background:#dcae86' },
  { name: 'checker', css: 'background-color:#bbb;background-image:linear-gradient(45deg,#888 25%,transparent 25%),linear-gradient(-45deg,#888 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#888 75%),linear-gradient(-45deg,transparent 75%,#888 75%);background-size:16px 16px;background-position:0 0,0 8px,8px -8px,-8px 0' },
];

const heads = manifest.layers.headgear.map((l) => l.id);
const rows = heads.map((id) => {
  const cells = backgrounds.map((b) => `<td><div class="swatch" style="${b.css}">${inline(id)}</div></td>`).join('');
  return `<tr><th>${id}</th>${cells}</tr>`;
}).join('');

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>v2 headgear fill audit</title>
<style>
  body { margin:0; background:#1c1e22; color:#e8e4d8; font:13px system-ui,sans-serif; }
  header { padding:18px 22px 4px; }
  header h1 { margin:0; font-size:18px; }
  header p { margin:6px 0 0; opacity:.65; }
  table { border-collapse:collapse; margin:14px 22px 40px; }
  th { text-align:right; padding:8px 12px; font:12px ui-monospace,monospace; opacity:.8; vertical-align:middle; }
  td { padding:8px; }
  thead th { text-align:center; text-transform:uppercase; letter-spacing:.06em; font-size:11px; }
  .swatch { width:150px; height:200px; border-radius:8px; border:1px solid #3a3f47; overflow:hidden; }
  .swatch svg { width:100%; height:100%; display:block; }
</style></head><body>
  <header><h1>v2 headgear fill audit</h1>
  <p>Each headgear layer rendered alone. Every cap must show a solid filled body on all four
     backgrounds — including the transparent checkerboard, where an unfilled cap would show the
     checker through its body.</p></header>
  <table>
    <thead><tr><th></th>${backgrounds.map((b) => `<th class="colhead">${b.name}</th>`).join('')}</tr></thead>
    <tbody>${rows}</tbody>
  </table>
</body></html>`;

mkdirSync(join(ROOT, 'docs/portraits'), { recursive: true });
writeFileSync(join(ROOT, 'docs/portraits/headgear-audit.html'), html, 'utf8');
console.log('Wrote docs/portraits/headgear-audit.html');
