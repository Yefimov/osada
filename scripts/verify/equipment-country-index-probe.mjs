// Equipment is fetched by the ids a battle REFERENCES, not by the nationalities it declares.
//
// Reported against Falciu 2 (`rcampfa2.xml`, the Black Sea Fleet campaign's second scenario): the
// Romanian objective at Tiganca (row 9, col 8) looked empty but could not be entered, and inspecting
// it revealed an invisible "No Class / Romania / City" occupant that could not be attacked and held
// the victory hex for the whole battle. The formation is eqid 16132 -- OG's immobile `Entrenched`
// infantry, icon `sbe04` -- written `flag="14"` under a player with `country="13"` and
// `support="14"`. All three resolve to merged country file 14; the record lives in file 13, which
// was therefore never fetched, so `Equipment.getEquipment(16132)` answered nothing and
// `canInitiateAttackOnUnitType` refused every attack on it.
//
// The fix is `resources/equipment/eqp-united/equipment-index.json` plus
// `model/EquipmentCountryIndex`: an eqid -> country-file map, consulted with the ids a scenario or
// a save actually carries. This probe checks both halves of that:
//
//   A. STATIC -- the sidecar agrees with the country files record for record, and every equipment
//      reference in every deployed scenario resolves through it. It also re-counts the references
//      that lie outside the old player-list guess, which is the size of the bug.
//   B. LIVE -- `rcampfa2.xml` in a real browser: country file 13 is fetched, Tiganca's garrison
//      comes up with its real class, icon and combat stats, and an adjacent Soviet formation can
//      legally target it. The battle is then saved and restored -- a restore rebuilds from the save
//      alone and has its own collector -- and four more scenarios from the audit's worst offenders
//      are scanned for any unit whose record still fails to resolve.
//
// Combat itself is not driven here -- no attack entry point is `@JsExport`ed, so the assault and
// the capture that follows it live in `TigancaGarrisonTest`, which uses this same shipped record.
//
// Usage: node scripts/verify/equipment-country-index-probe.mjs
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..', '..');
const RES = path.join(ROOT, 'src', 'jsMain', 'resources', 'resources');
const EQUIP_DIR = path.join(RES, 'equipment', 'eqp-united');
const SCENARIO_DIR = path.join(RES, 'scenarios', 'data');
const DIST = path.join(ROOT, 'build', 'dist', 'js', 'developmentExecutable');
const PORT = 8846;

const problems = [];
const check = (ok, label, detail) => { if (!ok) problems.push(`${label}${detail ? ' -- ' + detail : ''}`); return ok; };

// ---------------------------------------------------------------- A. static

const index = JSON.parse(fs.readFileSync(path.join(EQUIP_DIR, 'equipment-index.json'), 'utf8'));
check(index.format === 1, 'equipment-index.json: unexpected format', String(index.format));
check(Array.isArray(index.segments) && index.segments.length > 0, 'equipment-index.json: no segments');

// Sorted and non-overlapping -- the runtime binary-searches these.
let ordered = true;
for (let i = 1; i < index.segments.length; i++) {
  if (index.segments[i][1] <= index.segments[i - 1][2]) { ordered = false; break; }
}
check(ordered, 'equipment-index.json: segments overlap or are unsorted');

const fileForEqid = (eqid) => {
  let lo = 0, hi = index.segments.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1, s = index.segments[mid];
    if (eqid < s[1]) hi = mid - 1; else if (eqid > s[2]) lo = mid + 1; else return s[0];
  }
  return null;
};

// Every record in every country file must be claimed by a segment pointing at THAT file, and no
// segment may claim an id no file holds. An index that disagrees with the data is worse than none.
const ownerOfRecord = new Map();
let recordCount = 0;
for (const name of fs.readdirSync(EQUIP_DIR)) {
  const m = /^equipment-country-(\d+)\.json$/.exec(name);
  if (!m) continue;
  const fileNumber = Number(m[1]);
  const data = JSON.parse(fs.readFileSync(path.join(EQUIP_DIR, name), 'utf8'));
  for (const key of Object.keys(data.units)) { ownerOfRecord.set(Number(key), fileNumber); recordCount++; }
}
const misindexed = [];
for (const [eqid, fileNumber] of ownerOfRecord) {
  if (fileForEqid(eqid) !== fileNumber) misindexed.push(`${eqid}->${fileForEqid(eqid)} (really ${fileNumber})`);
}
check(misindexed.length === 0, 'index disagrees with the country files', misindexed.slice(0, 5).join(', '));

let claimedNotPresent = 0;
for (const [, first, last] of index.segments.map(s => [s[0], s[1], s[2]])) {
  for (let eqid = first; eqid <= last; eqid++) if (!ownerOfRecord.has(eqid)) claimedNotPresent++;
}
check(claimedNotPresent === 0, 'index claims ids no country file holds', String(claimedNotPresent));

// Every equipment reference in every deployed scenario. `unit` covers all three placement sites --
// a hex, a reinforcement wave and an event spawn -- because all three use the same element.
const UNIT_ATTRS = ['id', 'transport', 'carrier'];
const scanned = { scenarios: 0, refs: 0, unresolved: [], outsidePlayerList: 0, outsideScenarios: new Set() };
for (const name of fs.readdirSync(SCENARIO_DIR)) {
  if (!name.endsWith('.xml')) continue;
  const xml = fs.readFileSync(path.join(SCENARIO_DIR, name), 'utf8');
  scanned.scenarios++;

  // The country files the OLD player-list guess would have fetched, so the count below stays
  // meaningful even as content changes.
  const guessed = new Set([0]);
  for (const pm of xml.matchAll(/<player\b[^>]*>/g)) {
    const country = /\bcountry="(\d+)"/.exec(pm[0]);
    if (country) guessed.add(Number(country[1]) + 1);
    const support = /\bsupport="([^"]*)"/.exec(pm[0]);
    if (support) for (const raw of support[1].split(',')) { const v = Number(raw.trim()); if (v > 0) guessed.add(v); }
  }

  const refs = [];
  for (const um of xml.matchAll(/<unit\b[^>]*>/g)) {
    for (const attr of UNIT_ATTRS) {
      const m = new RegExp(`\\b${attr}="(-?\\d+)"`).exec(um[0]);
      if (m && Number(m[1]) > 0) refs.push(Number(m[1]));
    }
  }
  for (const hm of xml.matchAll(/<hex\b[^>]*\btrigequip="(\d+)"/g)) if (Number(hm[1]) > 0) refs.push(Number(hm[1]));

  for (const eqid of refs) {
    scanned.refs++;
    const owner = fileForEqid(eqid);
    if (owner === null || !ownerOfRecord.has(eqid)) {
      if (scanned.unresolved.length < 10) scanned.unresolved.push(`${name}: ${eqid}`);
      continue;
    }
    if (!guessed.has(owner)) { scanned.outsidePlayerList++; scanned.outsideScenarios.add(name); }
  }
}
check(scanned.unresolved.length === 0, 'scenario references an eqid the index cannot resolve',
  scanned.unresolved.slice(0, 5).join(' | '));
// Not an assertion, a measurement: if this ever drops to zero the bug is gone from the content and
// the index has become belt-and-braces rather than load-bearing.
check(scanned.outsidePlayerList > 0, 'no reference lies outside the player-list guess any more',
  'the corpus changed; re-read this probe before deleting anything');

console.log('=== A. static ===');
console.log(`  index            : ${index.segments.length} segments over ${recordCount} records`);
console.log(`  scenarios        : ${scanned.scenarios}, ${scanned.refs} equipment references`);
console.log(`  player-list guess: ${scanned.outsidePlayerList} references in ${scanned.outsideScenarios.size} scenarios would have been MISSED`);

// ------------------------------------------------------------------ B. live

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const server = await new Promise(res => {
  const s = http.createServer((rq, rs) => {
    const raw = decodeURIComponent(rq.url.split('?')[0]);
    const fp = path.join(DIST, raw === '/' ? 'index.html' : raw);
    fs.readFile(fp, (e, d) => {
      if (e) { rs.writeHead(404); rs.end(); return; }
      rs.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' });
      rs.end(d);
    });
  });
  s.listen(PORT, () => res(s));
});
const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage(); await page.setViewport({ width: 1920, height: 1080 });
const errs = []; page.on('pageerror', e => errs.push(e.message.slice(0, 200)));
const requested = new Set(); page.on('request', r => requested.add(r.url().split('/').pop()));
const httpErrors = []; page.on('response', r => { if (r.status() >= 400) httpErrors.push(`${r.url().slice(-50)} -> ${r.status()}`); });

await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' }); await sleep(1800);

const loadScenario = async (file) => {
  await page.evaluate((s) => { window.game.campaign = null; window.game.newScenario(s, 'x'); }, file);
  await sleep(3500);
  await page.evaluate(() => {
    const sm = document.getElementById('startmenu'); if (sm) sm.style.display = 'none';
    const ok = document.getElementById('uiokbut'); if (ok) ok.click();
  });
  await sleep(700);
};

/** Every placed formation whose equipment record failed to resolve: no name, no class, no icon. */
const unresolvedUnits = () => page.evaluate(() => {
  const map = window.game.scenario.map, grid = map.map, out = [];
  const bad = (u) => { const d = u.unitData(); return d.uclass === 0 && d.name === '' && d.icon === ''; };
  for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
    const h = grid[r][c];
    for (const u of [h.unit, h.airunit]) if (u && bad(u)) out.push(`${r},${c} eqid=${u.eqid}`);
  }
  return out;
});

await loadScenario('rcampfa2.xml');

check(requested.has('equipment-index.json'), 'the equipment index was never fetched');
check(requested.has('equipment-country-13.json'),
  'country file 13 was not fetched -- Tiganca\'s garrison lives there', [...requested].filter(u => u.startsWith('equipment-country')).join(', '));

const tiganca = await page.evaluate(() => {
  const grid = window.game.scenario.map.map, h = grid[9][8], u = h.unit;
  if (!u) return { present: false };
  const d = u.unitData();
  return {
    present: true, hex: h.name, victorySide: h.victorySide, eqid: u.eqid, flag: u.flag,
    strength: u.strength, experience: u.experience,
    name: d.name, uclass: d.uclass, icon: d.icon, softatk: d.softatk, hardatk: d.hardatk,
    grounddef: d.grounddef, ammo: d.ammo, movpoints: d.movpoints, country: d.country,
  };
});

check(tiganca.present, 'rcampfa2: nothing on the Tiganca hex at all');
if (tiganca.present) {
  check(tiganca.eqid === 16132, 'rcampfa2: unexpected garrison eqid', String(tiganca.eqid));
  check(tiganca.name === 'Entrenched', 'rcampfa2: the garrison has no name', JSON.stringify(tiganca.name));
  check(tiganca.uclass === 1, 'rcampfa2: the garrison is still "No Class"', `uclass=${tiganca.uclass}`);
  check(/sbe04\.png$/.test(tiganca.icon), 'rcampfa2: the garrison has the wrong icon', tiganca.icon);
  check(tiganca.softatk === 10 && tiganca.hardatk === 3 && tiganca.grounddef === 15 && tiganca.ammo === 12,
    'rcampfa2: the garrison has no combat stats', JSON.stringify(tiganca));
  check(tiganca.movpoints === 0, 'rcampfa2: an entrenchment should not move', String(tiganca.movpoints));
  check(tiganca.country === 13 && tiganca.flag === 14,
    'rcampfa2: the record country / unit flag pair is not the reported one -- this probe may be stale',
    `country=${tiganca.country} flag=${tiganca.flag}`);
}

// The reported symptom was "it cannot be attacked", which `canInitiateAttackOnUnitType` decides
// from whether BOTH records are present. Fog is disabled and a Soviet formation is placed next to
// the objective so the answer depends only on the record, not on what the front line happens to
// have reached by turn 1. `uiUnitSelect` is the exported entry the player's own click goes through.
const targeting = await page.evaluate(() => {
  const g = window.game, map = g.scenario.map, grid = map.map;
  for (const k of Object.keys(window.uiSettings)) if (/^noFOW/.test(k)) window.uiSettings[k] = true;
  let soviet = null, from = null;
  for (let r = 0; r < map.rows && !soviet; r++) for (let c = 0; c < map.cols && !soviet; c++) {
    const h = grid[r][c];
    const d = h.unit && h.unit.unitData();
    if (h.unit && h.unit.owner === 0 && d.movpoints > 0 && d.gunrange >= 1 && d.softatk > 0) { soviet = h.unit; from = h; }
  }
  if (!soviet) return { error: 'no Soviet ground formation on the map' };
  const before = grid[9][8].isAttackSel;
  from.delUnit(soviet);
  grid[9][9].setUnit(soviet);
  g.ui.uiUnitSelect(soviet);
  return { attacker: soviet.unitData().name, before, after: grid[9][8].isAttackSel };
});
check(!targeting.error, 'rcampfa2: could not set up the targeting check', targeting.error);
check(targeting.before === false, 'rcampfa2: the objective was already flagged as a target before selection');
check(targeting.after === true, 'rcampfa2: the garrison is STILL not a legal target', JSON.stringify(targeting));

const falciuUnresolved = await unresolvedUnits();
check(falciuUnresolved.length === 0, 'rcampfa2: units with unresolved equipment', falciuUnresolved.slice(0, 5).join(' | '));

// The other half of the fix. A restore rebuilds the battle from the save alone and never re-reads
// the scenario XML, so it needs its own collector (`SavedEquipmentScan`) -- and the previous scenario
// is torn down first, which means an equipment map that was correct a moment ago proves nothing.
// The save is taken AFTER the targeting check above, so the Soviet formation moved next to the
// objective travels in it too.
const roundTrip = await page.evaluate(() => new Promise((resolve) => {
  const g = window.game;
  const payload = g.state.exportGameState();
  g.state.restoreFromString(payload, () => {
    const grid = g.scenario.map.map, u = grid[9][8].unit;
    const d = u && u.unitData();
    resolve(u ? { eqid: u.eqid, name: d.name, uclass: d.uclass, grounddef: d.grounddef } : { missing: true });
  });
}));
await sleep(1500);
check(!roundTrip.missing, 'save restore: the Tiganca garrison did not come back');
check(roundTrip.name === 'Entrenched' && roundTrip.uclass === 1 && roundTrip.grounddef === 15,
  'save restore: the garrison came back as an empty record', JSON.stringify(roundTrip));

console.log('\n=== B. live ===');
console.log(`  rcampfa2.xml     : Tiganca holds "${tiganca.name}" (class ${tiganca.uclass}, ` +
  `${tiganca.softatk}/${tiganca.hardatk} atk, ${tiganca.grounddef} def), targetable by ${targeting.attacker}`);
console.log(`  save round-trip  : came back as "${roundTrip.name}" (class ${roundTrip.uclass}, ${roundTrip.grounddef} def)`);

// The worst offenders from the static audit, each referencing equipment from a country file its own
// player list never names.
const MORE = ['acampbac.xml', 'acampoms.xml', 'battle_cuito.xml', 'acampsem.xml'];
for (const file of MORE) {
  await loadScenario(file);
  const unresolved = await unresolvedUnits();
  const placed = await page.evaluate(() => {
    const map = window.game.scenario.map, grid = map.map; let n = 0;
    for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
      if (grid[r][c].unit) n++; if (grid[r][c].airunit) n++;
    }
    return n;
  });
  check(placed > 0, `${file}: no units placed`);
  check(unresolved.length === 0, `${file}: units with unresolved equipment`, unresolved.slice(0, 5).join(' | '));
  console.log(`  ${file.padEnd(17)}: ${placed} units placed, ${unresolved.length} unresolved`);
}

check(errs.length === 0, 'runtime JS errors', errs.slice(0, 4).join(' | '));
check(httpErrors.length === 0, 'HTTP 4xx/5xx', httpErrors.slice(0, 4).join(' | '));

console.log('\n=== Equipment country index probe ===');
console.log(`Runtime errors   : ${errs.length}`);
console.log(`HTTP 4xx/5xx     : ${httpErrors.length}`);
if (problems.length) { console.log('\nFAILURES:'); problems.forEach(p => console.log('  ! ' + p)); }
console.log(`\nOverall: ${problems.length === 0 ? 'PASS' : 'FAIL'}`);

await browser.close(); server.close();
process.exit(problems.length === 0 ? 0 : 1);
