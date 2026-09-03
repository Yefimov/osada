// Validates the v2 portrait library against its manifest and composition rules.
//   node scripts/portraits/validate-v2.mjs
//
// Checks: files exist, viewBox is 0 0 300 400, ids unique; every composition resolves; hair
// suppression obeys the headgear mode; composition is deterministic; HEADGEAR fill integrity
// (>=1 filled closed shape, sufficient filled coverage, main body not transparent, var fills have
// fallbacks); the HARD female facial-hair invariant over 10k portraits; and face archetypes carry
// no embedded facial-hair geometry.

import { readFileSync, existsSync, readdirSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve, join } from 'path';
import { compose, layerPath } from '../../src/jsMain/resources/portraits/portrait-core-v2.mjs';

const RES = resolve(dirname(fileURLToPath(import.meta.url)), '../../src/jsMain/resources/portraits');
const manifest = JSON.parse(readFileSync(join(RES, 'v2/manifest.json'), 'utf8'));

const errors = [];
const fail = (m) => errors.push(m);

// ---------- SVG filled-area analysis (pure, no rasteriser) ----------

function subpaths(d) {
  const seq = [];
  let cur = null;
  for (const m of d.matchAll(/([a-zA-Z])|(-?\d*\.?\d+(?:[eE]-?\d+)?)/g)) {
    if (m[1]) { cur = { cmd: m[1], args: [] }; seq.push(cur); }
    else if (cur) cur.args.push(parseFloat(m[2]));
  }
  const subs = [];
  let pts = [], sx = 0, sy = 0, cx = 0, cy = 0;
  const close = (closed) => { if (pts.length > 1) subs.push({ pts, closed }); pts = []; };
  const line = (x, y) => { pts.push([x, y]); cx = x; cy = y; };
  const cubic = (x1, y1, x2, y2, x, y) => {
    const N = 10, ax = cx, ay = cy;
    for (let k = 1; k <= N; k++) {
      const t = k / N, u = 1 - t;
      pts.push([u * u * u * ax + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
        u * u * u * ay + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y]);
    }
    cx = x; cy = y;
  };
  const quad = (x1, y1, x, y) => {
    const N = 8, ax = cx, ay = cy;
    for (let k = 1; k <= N; k++) {
      const t = k / N, u = 1 - t;
      pts.push([u * u * ax + 2 * u * t * x1 + t * t * x, u * u * ay + 2 * u * t * y1 + t * t * y]);
    }
    cx = x; cy = y;
  };
  for (const c of seq) {
    const a = c.args, rel = c.cmd === c.cmd.toLowerCase(), C = c.cmd.toUpperCase();
    const X = (v) => (rel ? cx + v : v), Y = (v) => (rel ? cy + v : v);
    if (C === 'M') { close(false); const x = X(a[0]), y = Y(a[1]); sx = x; sy = y; cx = x; cy = y; pts = [[x, y]];
      for (let i = 2; i + 1 < a.length; i += 2) line(X(a[i]), Y(a[i + 1])); }
    else if (C === 'L') { for (let i = 0; i + 1 < a.length; i += 2) line(X(a[i]), Y(a[i + 1])); }
    else if (C === 'H') { for (const v of a) line(rel ? cx + v : v, cy); }
    else if (C === 'V') { for (const v of a) line(cx, rel ? cy + v : v); }
    else if (C === 'C') { for (let i = 0; i + 5 < a.length; i += 6) cubic(X(a[i]), Y(a[i + 1]), X(a[i + 2]), Y(a[i + 3]), X(a[i + 4]), Y(a[i + 5])); }
    else if (C === 'Q') { for (let i = 0; i + 3 < a.length; i += 4) quad(X(a[i]), Y(a[i + 1]), X(a[i + 2]), Y(a[i + 3])); }
    else if (C === 'Z') { close(true); cx = sx; cy = sy; pts = [[sx, sy]]; }
    else if (a.length >= 2) line(X(a[a.length - 2]), Y(a[a.length - 1]));
  }
  close(false);
  return subs;
}

const shoelace = (pts) => {
  let s = 0;
  for (let i = 0; i < pts.length; i++) { const [x1, y1] = pts[i], [x2, y2] = pts[(i + 1) % pts.length]; s += x1 * y2 - x2 * y1; }
  return Math.abs(s) / 2;
};
const pathArea = (d) => subpaths(d).filter((s) => s.closed).reduce((t, s) => t + shoelace(s.pts), 0);
const attr = (a, n) => { const m = a.match(new RegExp(`\\b${n}="([^"]*)"`)); return m ? m[1] : undefined; };
const numAttr = (a, n) => parseFloat(attr(a, n) || '0');

function resolveFill(a, varMiss, id) {
  const raw = attr(a, 'fill');
  if (raw === undefined) return 'black'; // SVG default fill is black (filled)
  if (raw.startsWith('var(')) {
    const fb = raw.match(/var\(\s*--[^,]+,\s*([^)]+)\)/);
    if (!fb) { varMiss.push(`${id}: fill ${raw} has no fallback`); return 'none'; }
    return fb[1].trim();
  }
  return raw;
}
const isFilled = (fill, a) =>
  fill && fill !== 'none' && fill !== 'transparent' && attr(a, 'fill-opacity') !== '0' && attr(a, 'opacity') !== '0';

function analyze(text, id, varMiss) {
  const shapes = [];
  const add = (type, a, area) => { const fill = resolveFill(a, varMiss, id); shapes.push({ type, area, filled: isFilled(fill, a), closed: type !== 'openpath' }); };
  for (const m of text.matchAll(/<path\b([^>]*?)\/?>/g)) {
    const d = attr(m[1], 'd'); if (!d) continue;
    const closed = /[zZ]/.test(d);
    add(closed ? 'path' : 'openpath', m[1], closed ? pathArea(d) : 0);
  }
  for (const m of text.matchAll(/<rect\b([^>]*?)\/?>/g)) add('rect', m[1], numAttr(m[1], 'width') * numAttr(m[1], 'height'));
  for (const m of text.matchAll(/<circle\b([^>]*?)\/?>/g)) { const r = numAttr(m[1], 'r'); add('circle', m[1], Math.PI * r * r); }
  for (const m of text.matchAll(/<ellipse\b([^>]*?)\/?>/g)) add('ellipse', m[1], Math.PI * numAttr(m[1], 'rx') * numAttr(m[1], 'ry'));
  return shapes;
}

// ---------- 1. Files, viewBox, ids ----------

const seen = new Set();
let fileCount = 0;
const varMiss = [];
const MIN_MAX_AREA = 2000; // largest filled closed shape
const MIN_TOTAL_AREA = 3000; // summed filled closed coverage

for (const category of Object.keys(manifest.layers)) {
  for (const layer of manifest.layers[category]) {
    if (seen.has(layer.id)) fail(`duplicate id: ${layer.id}`);
    seen.add(layer.id);
    const abs = join(RES, layerPath(manifest, layer.id));
    if (!existsSync(abs)) { fail(`missing file: ${layer.id}`); continue; }
    fileCount++;
    const svg = readFileSync(abs, 'utf8');
    if (!/viewBox="0 0 300 400"/.test(svg)) fail(`${layer.id}: wrong viewBox`);
    if (!/<svg[\s>]/.test(svg) || !/<\/svg>/.test(svg)) fail(`${layer.id}: malformed svg`);

    // Every headgear must be a real, filled, visible silhouette.
    if (category === 'headgear') {
      const shapes = analyze(svg, layer.id, varMiss);
      const filledClosed = shapes.filter((s) => s.filled && s.closed && s.area > 0);
      if (filledClosed.length === 0) { fail(`${layer.id}: no filled closed shape (renders as outline only)`); continue; }
      const maxArea = Math.max(...filledClosed.map((s) => s.area));
      const total = filledClosed.reduce((t, s) => t + s.area, 0);
      const biggestClosed = shapes.filter((s) => s.closed).sort((x, y) => y.area - x.area)[0];
      if (!biggestClosed.filled) fail(`${layer.id}: largest shape is transparent (main body relies on background)`);
      if (maxArea < MIN_MAX_AREA) fail(`${layer.id}: main filled body too small (${Math.round(maxArea)} < ${MIN_MAX_AREA})`);
      if (total < MIN_TOTAL_AREA) fail(`${layer.id}: filled coverage too low (${Math.round(total)} < ${MIN_TOTAL_AREA})`);
    }
  }
}
varMiss.forEach(fail);

// ---------- 2. Face archetypes carry no facial-hair geometry ----------

for (const f of readdirSync(join(RES, 'v2/layers/face'))) {
  const t = readFileSync(join(RES, 'v2/layers/face', f), 'utf8');
  if (/--hair/.test(t)) fail(`face ${f} references --hair (possible embedded hair)`);
  if (/beard|mustache|moustache|stubble/i.test(t)) fail(`face ${f} contains facial-hair geometry`);
}

// ---------- 3. Composition coverage, determinism, hair suppression ----------

const ranks = ['lieutenant', 'captain', 'major', 'colonel'];
const genders = ['male', 'female'];
const seasons = ['summer', 'winter'];
const required = ['background', 'uniform_back', 'face', 'uniform_front_collar', 'rank', 'branch'];

let n = 0;
for (const [poolId, pool] of Object.entries(manifest.pools))
  for (const branch of pool.branches)
    for (const rank of ranks)
      for (const gender of genders)
        for (const season of seasons)
          for (let s = 0; s < 12; s++) {
            const input = { branch, rank, gender, season, poolId };
            const a = compose(manifest, input, 500 + n);
            const b = compose(manifest, input, 500 + n);
            n++;
            if (JSON.stringify(a.layerIds) !== JSON.stringify(b.layerIds)) fail(`nondeterministic: ${poolId}/${branch}/${rank}`);
            for (const id of a.layerIds) if (!existsSync(join(RES, layerPath(manifest, id)))) fail(`unknown composed id ${id}`);
            for (const cat of required) if (!a.byCategory[cat]) fail(`missing required '${cat}' for ${poolId}/${branch}/${rank}`);
            const mode = a.meta.hairMode;
            // A hat hides the crown: no front fringe or temple hair. Back/nape hair may still show
            // (females keep long hair below a hat); males simply have none there.
            if ((mode === 'UNDER_FUR_HAT' || mode === 'UNDER_FLIGHT_HELMET') &&
                (a.byCategory.hair_front || a.byCategory.under_headgear_hair)) fail(`${mode} exposed crown hair`);
            if (mode === 'UNDER_CAP' && a.byCategory.hair_front) fail('UNDER_CAP kept full front hair');
          }

// ---------- 4. HARD female facial-hair invariant, 10k+ portraits ----------

let femaleBad = 0;
let femaleBald = 0;
const poolIds = Object.keys(manifest.pools);
for (let s = 0; s < 12000; s++) {
  const poolId = poolIds[s % poolIds.length];
  const branches = manifest.pools[poolId].branches;
  const input = { branch: branches[s % branches.length], rank: ranks[(s >> 2) % 4], gender: 'female', season: s % 2 ? 'winter' : 'summer', poolId };
  const r = compose(manifest, input, s * 2654435761);
  if (r.byCategory.facial_hair !== 'facial_clean') { femaleBad++; if (femaleBad <= 3) fail(`female non-clean at seed ${s}: ${r.byCategory.facial_hair}`); }
  const hasHair = r.byCategory.hair_back || r.byCategory.hair_front || r.byCategory.under_headgear_hair;
  if (!hasHair) { femaleBald++; if (femaleBald <= 3) fail(`female bald at seed ${s} (${r.meta.hairMode})`); }
}
if (femaleBad === 0 && femaleBald === 0) console.log('OK — 12000 female portraits: all facial_clean and none bald.');

if (errors.length) {
  console.error(`FAIL — ${errors.length} problem(s):`);
  for (const e of errors.slice(0, 40)) console.error('  - ' + e);
  process.exit(1);
}
console.log(`OK — v2: ${fileCount} files, ${seen.size} ids, ${n} compositions, headgear fill + female invariant verified.`);
