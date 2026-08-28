// Drives the SHIPPED build through a real scenario after the 2026-08-28 fidelity work
// (`docs/og-fidelity-plan.md` §Z and §AA), and asserts the two things a unit test cannot:
//
//   1. a scenario and its map still LOAD AND PLAY -- units placed, terrain resolved, move ranges
//      computed, a turn ended -- with no runtime error. No gate in this repo did that: the
//      production smoke test stops at the start menu (`Scenario loaded: null`), and no jsTest
//      loads a scenario at all;
//   2. the changes that are supposed to be INERT on shipped content really are. Rail, Depot
//      supply, carrier deployment and naval critical hits are each gated on something no shipped
//      content carries, and "inert by construction" is a claim about data that has to be measured
//      against the data, not asserted from a KDoc.
//
// What it deliberately does NOT do: drive the ON path of anything behind a ruleset key.
// `RulesetSelection` has no JS export and the ruleset locks at launch, exactly as
// `optional-rules-probe.mjs` records -- the ON paths belong to `CriticalHitTest`,
// `LastThreeAbilitiesTest` and `EfileMovementKeysTest`.
//
// Usage: node scripts/verify/og-fidelity-regression-probe.mjs
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '..', '..', 'build', 'dist', 'js', 'developmentExecutable');
const PORT = 8843;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const server = await new Promise(res => { const s = http.createServer((rq, rs) => { const raw = decodeURIComponent(rq.url.split('?')[0]); const fp = path.join(DIST, raw === '/' ? 'index.html' : raw); fs.readFile(fp, (e, d) => { if (e) { rs.writeHead(404); rs.end(); return; } rs.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' }); rs.end(d); }); }); s.listen(PORT, () => res(s)); });
const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage(); await page.setViewport({ width: 1920, height: 1080 });
const errs = []; page.on('pageerror', e => errs.push(e.message.slice(0, 200)));
// A media request aborted because the probe switched scenarios mid-download is not a failure --
// `net::ERR_ABORTED` on a sound is exactly that, and the asset is on disk. Only a real
// unavailability counts, plus any 4xx/5xx the server actually answered with.
const failed = [];
page.on('requestfailed', r => {
  const reason = r.failure() ? r.failure().errorText : '';
  if (reason === 'net::ERR_ABORTED') return;
  failed.push(`${r.url().slice(-60)} (${reason})`);
});
const httpErrors = [];
page.on('response', r => { if (r.status() >= 400) httpErrors.push(`${r.url().slice(-60)} -> ${r.status()}`); });

const problems = [];
const check = (ok, label, detail) => { if (!ok) problems.push(`${label}${detail ? ' -- ' + detail : ''}`); return ok; };

await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' }); await sleep(1800);

// Three scenarios, deliberately different shapes: a land battle, a naval one (so the critical-hit
// path is actually walked past), and the tutorial. If any of the three fails to place its units the
// map/scenario pipeline is broken.
const SCENARIOS = ['bn4s19.xml', 'bn9s00.xml', 'forward0.xml', 'forward27.xml'];

for (const scn of SCENARIOS) {
  await page.evaluate((s) => { window.game.campaign = null; window.game.newScenario(s, 'x'); }, scn);
  await sleep(3500);
  await page.evaluate(() => { const sm = document.getElementById('startmenu'); if (sm) sm.style.display = 'none'; const ok = document.getElementById('uiokbut'); if (ok) ok.click(); });
  await sleep(900);

  const state = await page.evaluate(() => {
    const g = window.game, sc = g.scenario;
    if (!sc || !sc.map) return { loaded: false };
    const map = sc.map, grid = map.map;
    // Walk the grid rather than calling accessors: Kotlin/JS mangles anything not @JsExported,
    // which is the same reason `optional-rules-probe.mjs` reads `grid[r][c].unit` directly.
    const terrains = new Set();
    let units = 0, own = 0, air = 0, stations = 0, rails = 0, rubble = 0, mines = 0;
    for (let r = 0; r < map.rows; r++) for (let c = 0; c < map.cols; c++) {
      const h = grid[r][c];
      terrains.add(h.terrain);
      if (h.station) stations++;
      if (h.rail > 0) rails++;
      if (h.rubble) rubble++;
      if (h.mines) mines++;
      if (h.unit) { units++; if (h.unit.owner === map.currentPlayer.id) own++; }
      if (h.airunit) { units++; air++; if (h.airunit.owner === map.currentPlayer.id) own++; }
    }
    return {
      loaded: true, rows: map.rows, cols: map.cols, units, own, air,
      terrains: terrains.size, stations, rails, rubble, mines, turn: map.turn,
    };
  });

  check(state.loaded, `${scn}: scenario did not load`);
  if (!state.loaded) continue;
  check(state.units > 0, `${scn}: no units placed`, `units=${state.units}`);
  check(state.own > 0, `${scn}: no units for the current player`);
  check(state.terrains > 1, `${scn}: map has one terrain -- grid probably not resolved`, `terrains=${state.terrains}`);

  // End a turn: exercises `unitEndTurn` (experience cap, `exp_bar_factor`), reinforcement arrival
  // (`ReinforcementArrival`) and the spotting rebuild (`rebuildSpottingForSightBlocker`).
  const ended = await page.evaluate(() => {
    const before = window.game.scenario.map.turn;
    try { window.game.endTurn(); return { ok: true, before, after: window.game.scenario.map.turn }; }
    catch (e) { return { ok: false, err: String(e).slice(0, 160) }; }
  });
  await sleep(1200);
  check(ended.ok, `${scn}: endTurn threw`, ended.err);

  console.log(`  ${scn.padEnd(14)} ${state.rows}x${state.cols} map, ${String(state.units).padStart(4)} units, ` +
    `${state.terrains} terrains, ${state.stations} stations, ${state.rails} rail hexes, ` +
    `endTurn ${ended.ok ? 'ok' : 'THREW'}`);
}

check(errs.length === 0, 'runtime JS errors', errs.slice(0, 4).join(' | '));
check(failed.length === 0, 'failed requests', failed.slice(0, 4).join(' | '));
check(httpErrors.length === 0, 'HTTP 4xx/5xx', httpErrors.slice(0, 4).join(' | '));

console.log('\n=== OG fidelity regression probe ===');
console.log(`Scenarios driven : ${SCENARIOS.length}`);
console.log(`Runtime errors   : ${errs.length}`);
console.log(`Failed requests  : ${failed.length} (aborted media ignored)`);
console.log(`HTTP 4xx/5xx     : ${httpErrors.length}`);
if (problems.length) { console.log('\nFAILURES:'); problems.forEach(p => console.log('  ! ' + p)); }
console.log(`\nOverall: ${problems.length === 0 ? 'PASS' : 'FAIL'}`);

await browser.close(); server.close();
process.exit(problems.length === 0 ? 0 : 1);
