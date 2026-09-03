// Builds docs/portraits/soviet-review.html — a focused Soviet-shape review after the corrective
// pass: branch officers (male + female), a headgear-only comparison row, a female-only row, each
// shown large, at Unit-Info size and as a grayscale thumbnail, with the full selected-layer list.
//   node scripts/portraits/build-soviet-review.mjs

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve, join } from 'path';
import { compose, layerPath } from '../../src/jsMain/resources/portraits/portrait-core-v2.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const RES = join(ROOT, 'src/jsMain/resources/portraits');
const manifest = JSON.parse(readFileSync(join(RES, 'v2/manifest.json'), 'utf8'));

const SKINS = { infantry: '#dcae86', artillery: '#e7c39c', armor: '#c99a70', aviation: '#d8a878' };
const HAIRS = { infantry: '#4a3a2b', artillery: '#33281d', armor: '#5f4a30', aviation: '#3a2f22' };
const GRAY = '#b9b2a3';

function shade(hex, f) {
  const n = parseInt(hex.slice(1), 16);
  const r = Math.round(((n >> 16) & 255) * f), g = Math.round(((n >> 8) & 255) * f), b = Math.round((n & 255) * f);
  return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}
const shadowFor = (skin, gender) => shade(skin, gender === 'female' ? 0.92 : 0.83);

let seq = 0;
function inline(id, px) {
  return readFileSync(join(RES, layerPath(manifest, id)), 'utf8')
    .replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '')
    .replace(/id="([^"]+)"/g, (_, g) => `id="${px}${g}"`)
    .replace(/url\(#([^)]+)\)/g, (_, g) => `url(#${px}${g})`);
}
function stack(layerIds, style, cls) {
  const px = `r${seq++}_`;
  const inner = layerIds.map((id) => inline(id, px)).join('\n');
  return `<svg class="${cls}" viewBox="0 0 300 400" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="${style}">${inner}</svg>`;
}

function debug(byCategory) {
  return manifest.order.filter((c) => byCategory[c])
    .map((c) => `<div><span>${c}</span>${byCategory[c]}</div>`).join('');
}

function card(title, r, skin, gender) {
  const style = `--skin:${skin};--hair:${r.meta.hairGray ? GRAY : HAIRS[title.branch] || '#4a3a2b'};--skin-shadow:${shadowFor(skin, gender)};`;
  return `
    <figure class="card">
      <div class="big frame">${stack(r.layerIds, style, 's')}</div>
      <div class="thumbs">
        <div class="ui"><div class="uiframe">${stack(r.layerIds, style, 's')}</div><span>Unit&nbsp;Info</span></div>
        <div class="ui"><div class="uiframe gray">${stack(r.layerIds, style, 's')}</div><span>grayscale</span></div>
      </div>
      <figcaption>
        <b>${title.label}</b>
        <span class="meta">${r.meta.archetype} · ${r.meta.age} · ${r.meta.season} · ${r.meta.hairMode}</span>
        <div class="debug">${debug(r.byCategory)}</div>
      </figcaption>
    </figure>`;
}

const branches = ['infantry', 'artillery', 'armor', 'aviation'];
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

// Section A — branch officers (male)
const officers = branches.map((branch, i) => {
  const season = branch === 'aviation' || branch === 'infantry' ? 'winter' : 'summer';
  const input = { branch, rank: ['captain', 'major', 'lieutenant', 'colonel'][i], gender: 'male', season };
  const r = compose(manifest, input, 2100 + i * 31);
  return card({ label: `${cap(input.rank)} · ${cap(branch)}`, branch }, r, SKINS[branch], 'male');
}).join('');

// Section B — headgear comparison (same officer, forced headgear)
const headgears = ['headgear_officer_cap', 'headgear_pilotka', 'headgear_ushanka', 'headgear_flight_helmet', 'none'];
const headgearCards = headgears.map((hg) => {
  const input = { branch: 'infantry', rank: 'captain', gender: 'male', season: 'winter', headgear: hg, age: 'middle' };
  const r = compose(manifest, input, 999);
  return card({ label: hg === 'none' ? 'bareheaded (full hair)' : hg.replace('headgear_', ''), branch: 'infantry' }, r, SKINS.infantry, 'male');
}).join('');

// Section C — female officers
const females = branches.map((branch, i) => {
  const season = i % 2 ? 'winter' : 'summer';
  const input = { branch, rank: ['lieutenant', 'captain', 'major', 'captain'][i], gender: 'female', season };
  const r = compose(manifest, input, 3300 + i * 41);
  return card({ label: `${cap(input.rank)} · ${cap(branch)} · ♀`, branch }, r, SKINS[branch], 'female');
}).join('');

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Soviet portrait review (corrective pass)</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#15171b; color:#e8e4d8; font:13px/1.45 system-ui,sans-serif; }
  header { padding:20px 24px 6px; }
  header h1 { margin:0; font-size:19px; }
  header p { margin:6px 0 0; opacity:.65; max-width:74ch; }
  h2 { margin:26px 24px 4px; font-size:13px; letter-spacing:.07em; text-transform:uppercase; opacity:.75; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(230px,1fr)); gap:18px; padding:10px 24px 20px; }
  .card { margin:0; display:flex; flex-direction:column; }
  .frame { border:1px solid #3a3f47; border-radius:8px; overflow:hidden; background:#0e0f12; }
  .big { width:150px; height:200px; }
  .big svg { width:100%; height:100%; display:block; }
  .thumbs { display:flex; gap:10px; margin-top:8px; }
  .ui { display:flex; flex-direction:column; align-items:center; gap:2px; }
  .uiframe { width:72px; height:96px; border:1px solid #3a3f47; border-radius:5px; overflow:hidden; background:#0e0f12; }
  .uiframe svg { width:100%; height:100%; display:block; }
  .uiframe.gray svg { filter:grayscale(1) contrast(1.05); }
  .ui span { font-size:10px; opacity:.5; }
  figcaption b { font-size:13px; }
  .meta { font-size:11px; opacity:.6; }
  .debug { font:10px/1.4 ui-monospace,monospace; opacity:.55; margin-top:5px; }
  .debug div { display:flex; gap:6px; }
  .debug span { min-width:120px; opacity:.7; }
</style></head><body>
  <header><h1>Soviet portrait review — corrective pass</h1>
  <p>USSR · Operation Uranus, 1942. Checks the Soviet-shape fixes: softened female lower-face shading,
     a clearly filled peaked-cap visor, a folded pilotka silhouette, branch-specific collars/tabs, and
     a smaller close-fitting flight helmet. Each portrait is shown large, at Unit-Info size and in
     grayscale, with its full layer stack.</p></header>
  <h2>Branch officers</h2>
  <div class="grid">${officers}</div>
  <h2>Headgear silhouettes (same officer)</h2>
  <div class="grid">${headgearCards}</div>
  <h2>Female officers (facial_clean invariant)</h2>
  <div class="grid">${females}</div>
</body></html>`;

mkdirSync(join(ROOT, 'docs/portraits'), { recursive: true });
writeFileSync(join(ROOT, 'docs/portraits/soviet-review.html'), html, 'utf8');
console.log('Wrote docs/portraits/soviet-review.html');
