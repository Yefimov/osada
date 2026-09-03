// Builds docs/portraits/female-audit.html — a female-only contact sheet for visual review of the
// facial-hair invariant. Every card is a female officer; the caption shows the facial_hair layer,
// which must always read "facial_clean".
//   node scripts/portraits/build-female-sheet.mjs

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve, join } from 'path';
import { compose, layerPath } from '../../src/jsMain/resources/portraits/portrait-core-v2.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const RES = join(ROOT, 'src/jsMain/resources/portraits');
const manifest = JSON.parse(readFileSync(join(RES, 'v2/manifest.json'), 'utf8'));

const SKINS = ['#e7c39c', '#dcae86', '#c99a70', '#b07d55'];
const HAIRS = ['#33281d', '#4a3a2b', '#5f4a30', '#7a5c38'];
const GRAY = '#b9b2a3';
const branches = ['infantry', 'armor', 'artillery', 'aviation'];
const ranks = ['lieutenant', 'captain', 'major', 'colonel'];

function shade(hex, f) {
  const n = parseInt(hex.slice(1), 16);
  const r = Math.round(((n >> 16) & 255) * f), g = Math.round(((n >> 8) & 255) * f), b = Math.round((n & 255) * f);
  return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}

let seq = 0;
function inline(id, px) {
  let svg = readFileSync(join(RES, layerPath(manifest, id)), 'utf8');
  return svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '')
    .replace(/id="([^"]+)"/g, (_, g) => `id="${px}${g}"`)
    .replace(/url\(#([^)]+)\)/g, (_, g) => `url(#${px}${g})`);
}

let anyNonClean = false;
const cards = [];
for (let i = 0; i < 32; i++) {
  const input = { branch: branches[i % 4], rank: ranks[(i >> 2) % 4], gender: 'female', season: i % 2 ? 'winter' : 'summer' };
  const r = compose(manifest, input, 700 + i * 29);
  if (r.byCategory.facial_hair !== 'facial_clean') anyNonClean = true;
  const px = `f${seq++}_`;
  const skin = SKINS[i % SKINS.length];
  const style = `--skin:${skin};--hair:${r.meta.hairGray ? GRAY : HAIRS[i % HAIRS.length]};--skin-shadow:${shade(skin, 0.92)};`;
  const inner = r.layerIds.map((id) => inline(id, px)).join('\n');
  cards.push(`
    <figure>
      <div class="frame" style="${style}"><svg viewBox="0 0 300 400" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">${inner}</svg></div>
      <figcaption><b>${input.rank} · ${input.branch}</b><span>facial: ${r.byCategory.facial_hair}</span></figcaption>
    </figure>`);
}

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>Female facial-hair audit</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#16181c; color:#e8e4d8; font:13px system-ui,sans-serif; }
  header { padding:20px 24px 4px; }
  header h1 { margin:0; font-size:19px; }
  header p { margin:6px 0 0; opacity:.65; max-width:70ch; }
  .banner { margin:10px 24px; padding:8px 12px; border-radius:6px; font-weight:600; }
  .ok { background:#1f3b25; color:#9fd0a6; }
  .bad { background:#4a1c1c; color:#e6a; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:16px; padding:16px 24px 40px; }
  figure { margin:0; }
  .frame { border:1px solid #3a3f47; border-radius:8px; overflow:hidden; aspect-ratio:3/4; background:#0e0f12; }
  .frame svg { width:100%; height:100%; display:block; }
  figcaption { padding:6px 2px 0; display:flex; flex-direction:column; }
  figcaption b { font-size:12px; }
  figcaption span { font-size:11px; opacity:.6; font-family:ui-monospace,monospace; }
</style></head><body>
  <header><h1>Female facial-hair audit</h1>
  <p>Every card below is a female officer. The invariant is hard, not weighted: the facial layer must
     always be <code>facial_clean</code>. The banner reflects an automated scan of these 32 cards.</p></header>
  <div class="banner ${anyNonClean ? 'bad' : 'ok'}">${anyNonClean ? 'FAIL — a female card selected non-clean facial hair' : 'PASS — all 32 female cards are facial_clean'}</div>
  <div class="grid">${cards.join('')}</div>
</body></html>`;

mkdirSync(join(ROOT, 'docs/portraits'), { recursive: true });
writeFileSync(join(ROOT, 'docs/portraits/female-audit.html'), html, 'utf8');
console.log(`Wrote docs/portraits/female-audit.html (anyNonClean=${anyNonClean})`);
