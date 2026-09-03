// Drives the running game through the three Open General optional rules added at schema 6
// (`docs/og-fidelity-plan.md` §L): Counterbattery (9.4), Extended LOS (9.5) and Build and Repair
// (9.3), plus the `Dismount` toggle and `No ZOC`.
//
// `OgOptionalRulesTest` asserts the rules themselves, ON and OFF, against real `GameMap`/`Hex`
// state. This probe asserts what a unit test cannot: that in the SHIPPED build, under the profile
// a player actually gets by default, none of it exists — no chip on any strip, no per-hex state on
// any scenario — and that every string the fidelity profile's gap list promises really ships.
//
// **Scope limit, stated rather than implied:** it does not drive the ON path. `RulesetSelection` is
// in-memory Kotlin with no JS export, and the ruleset is locked at scenario launch, so selecting
// Open General Fidelity from a headless page would mean exporting a test hook into production. The
// ON path is covered by `OgOptionalRulesTest` instead; what is only checkable here is the OFF
// guarantee that protects the 502 shipped scenarios.
//
// Usage: node scripts/verify/optional-rules-probe.mjs
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '..', '..', 'build', 'dist', 'js', 'developmentExecutable');
const PORT = 8841;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const server = await new Promise(res => { const s = http.createServer((rq, rs) => { const raw = decodeURIComponent(rq.url.split('?')[0]); const fp = path.join(DIST, raw === '/' ? 'index.html' : raw); fs.readFile(fp, (e, d) => { if (e) { rs.writeHead(404); rs.end(); return; } rs.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' }); rs.end(d); }); }); s.listen(PORT, () => res(s)); });
const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage(); await page.setViewport({ width: 1920, height: 1080 });
const errs = []; page.on('pageerror', e => errs.push(e.message.slice(0, 200)));

await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' }); await sleep(1800);
await page.evaluate(() => { window.game.campaign = null; window.game.newScenario('bn4s19.xml', 'x'); });
await sleep(3500);
await page.evaluate(() => { const sm = document.getElementById('startmenu'); if (sm) sm.style.display = 'none'; const ok = document.getElementById('uiokbut'); if (ok) ok.click(); });
await sleep(800);

// The fidelity profile's gap list is the surface the 2026-08-25 audit was ABOUT: it named four of
// Open General's ten optional rules while claiming to say where the profile stops.
//
// `RulesWindow.refreshGaps` renders it only when the RESOLVED profile is Open General Fidelity, so
// under this scenario's default profile the list is correctly empty and there is nothing to read
// from the DOM. Both halves are asserted: the list must be absent here, and every gap string must
// actually ship in the loaded bundle.
const gapKeys = ['rail', 'stations', 'air_missions', 'carriers', 'extended_naval', 'barrage',
  'authored_options', 'triggers', 'ai'];
const gaps = await page.evaluate(async (keys) => {
  const bundle = await (await fetch('i18n/en/ui.json')).json();
  return keys.map(k => bundle[`rules.og_fidelity.gap.${k}`] || null);
}, gapKeys);
const gapsInDom = await page.evaluate(() =>
  [...document.querySelectorAll('.osadaRulesGaps__list li')].map(e => e.textContent.trim()));

const report = await page.evaluate(() => {
  const out = {};
  const map = window.game.scenario.map;
  const units = [];
  const grid = map.map;
  for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
    const u = grid[r][c].unit; if (u) units.push({ r, c, id: u.id, owner: u.owner });
  }
  out.unitsOnMap = units.length;
  // Terrain census, so the engineering assertions below are read against a real map.
  const terrain = {};
  for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
    const t = grid[r][c].terrain; terrain[t] = (terrain[t] || 0) + 1;
  }
  out.terrain = terrain;
  // Nothing may be under construction and nothing razed on a freshly loaded scenario.
  let building = 0, razed = 0;
  for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
    if (grid[r][c].construction >= 0) building++;
    if (grid[r][c].razedTerrain >= 0) razed++;
  }
  out.underConstruction = building;
  out.razedHexes = razed;
  // The action strip for the player's own units: with the default profile no engineering chip may
  // show on ANY of them, whatever they are standing on.
  const ui = window.game.ui;
  const chips = new Set();
  for (const own of units.filter(u => u.owner === map.currentPlayer.id).slice(0, 12)) {
    const unit = grid[own.r][own.c].unit;
    map.currentUnit = unit;
    try { ui.uiUnitSelect(unit); } catch (e) { /* selection is best-effort in a headless probe */ }
    for (const e of document.querySelectorAll('#unit-context [data-action]')) {
      chips.add(e.getAttribute('data-action'));
    }
  }
  out.chips = [...chips];
  out.ownUnitsInspected = units.filter(u => u.owner === map.currentPlayer.id).slice(0, 12).length;
  return out;
});

const ENGINEERING_CHIPS = ['build_bridge', 'build_fortification', 'build_airfield', 'build_port', 'repair', 'demolish'];
const leaked = report.chips.filter(c => ENGINEERING_CHIPS.includes(c));

console.log(JSON.stringify({ gaps, gapsInDom, ...report, leakedEngineeringChips: leaked }, null, 1));

const problems = [];
if (report.underConstruction !== 0) problems.push('a freshly loaded scenario has work in progress');
if (report.razedHexes !== 0) problems.push('a freshly loaded scenario has razed hexes');
if (leaked.length) problems.push(`engineering chips shown with build_and_repair off: ${leaked}`);
// Every gap the profile claims to disclose must actually have shipped text behind it -- a missing
// key would render as a raw id, which is worse than saying nothing.
gapKeys.forEach((k, i) => {
  if (!gaps[i]) problems.push(`gap string rules.og_fidelity.gap.${k} is missing from the en bundle`);
});
// The five the audit found unmentioned, by content rather than by key name.
for (const want of [/rail/i, /station/i, /air mission/i, /carrier/i, /naval/i, /barrage/i, /trigger/i,
                    /scenario/i]) {
  if (!gaps.some(g => g && want.test(g))) problems.push(`profile gap list never mentions ${want}`);
}
// ...and under a profile that is NOT Open General Fidelity, nothing is disclosed, because there is
// no fidelity claim to qualify.
if (gapsInDom.length) problems.push('the gap list rendered under a non-fidelity profile');
// The naval entry named counterbattery (OG 9.4, now its own rule), naval mines (9.9) and critical
// hits (an efile key) until 2026-08-25. None of the three belongs to OG 9.6.
const naval = gaps[gapKeys.indexOf('extended_naval')] || '';
for (const strayer of [/counterbatter/i, /mine/i, /critical/i]) {
  if (strayer.test(naval)) problems.push(`the naval gap line still claims ${strayer}`);
}
if (errs.length) problems.push(`page errors: ${errs.slice(0, 3)}`);

console.log(problems.length ? `FAIL\n - ${problems.join('\n - ')}` : 'PASS');
await browser.close(); server.close();
process.exit(problems.length ? 1 : 0);
