/*
 * End-to-end probe for the Kiel prisoner sequence (docs/design/scenario-events.md).
 *
 * Unit tests cover the event runtime against a synthetic scenario. This drives the REAL
 * n_kiel.xml through the real ScenarioLoader, the real save layer and the real AI, and checks the
 * four things only a running game can answer:
 *
 *   1. nothing is standing in the detention compound at load (the original bug was an unarmed
 *      unit pre-placed there, destroyed on the loyalists' first activation)
 *   2. the alarm fires when a revolutionary combat unit closes to two hexes, and not before
 *   3. the loyalist AI attacks the detainees once they exist - normally, with no rules exception
 *   4. reaching the compound converts the survivors, and the whole thing survives a save
 *
 * Run: ./gradlew jsBrowserDistribution && node scripts/verify/kiel-prisoners-probe.mjs
 */
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';
import { getChromePath } from 'chrome-launcher';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '..', '..', 'build', 'dist', 'js', 'productionExecutable');
const PORT = 8831;
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.ogg': 'audio/ogg', '.mp3': 'audio/mpeg',
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

const ANCHOR = { row: 10, col: 34 };
const PRISONERS_EQID = 218;
const VOLKSMARINE_EQID = 46706;

const server = await new Promise(res => {
  const s = http.createServer((rq, rs) => {
    const raw = rq.url.split('?')[0];
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
const page = await browser.newPage();
const errs = [];
page.on('pageerror', e => errs.push(e.message.slice(0, 200)));
await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' });
await sleep(1600);

await page.evaluate(() => { window.game.campaign = null; window.game.newScenario('n_kiel.xml', 'x'); });
for (let i = 0; i < 60; i++) {
  const loaded = await page.evaluate(() => !!(window.game.scenario && window.game.scenario.isLoaded));
  if (loaded) break;
  await sleep(300);
}
await sleep(1500);
await page.evaluate(() => {
  const sm = document.getElementById('startmenu'); if (sm) sm.style.display = 'none';
  const ok = document.getElementById('uiokbut'); if (ok) ok.click();
});
await sleep(800);

// --- helpers injected into the page -----------------------------------------------------------
await page.evaluate(({ anchor }) => {
  window.__probe = {
    map: () => window.game.scenario.map,
    // Hex distance, the same odd-column formula HexGeometry.distance uses.
    dist: (r1, c1, r2, c2) => {
      const a = c2 % 2 === 1 ? 2 * r2 + 1 : 2 * r2;
      const b = c1 % 2 === 1 ? 2 * r1 + 1 : 2 * r1;
      const dx = Math.abs(a - b), dy = Math.abs(c2 - c1);
      return dx > dy ? ((dx - dy) / 2 + dy) : dy;
    },
    unitsWithEqid: eqid => {
      const grid = window.game.scenario.map.map, out = [];
      for (let r = 0; r < grid.length; r++) {
        for (let c = 0; c < grid[r].length; c++) {
          const u = grid[r][c] && grid[r][c].unit;
          if (u && u.eqid === eqid && !u.destroyed) {
            out.push({ row: r, col: c, strength: u.strength, owner: u.owner,
                       temporary: !!u.isTemporaryBorrowed, nodossier: !!u.nodossier });
          }
        }
      }
      return out;
    },
    // Relocate an existing unit and hand it to the revolutionary player, so the probe can put a
    // qualifying combat unit exactly where it needs one without inventing units the map has not
    // paid for. delUnit/setUnit keep the unit<->hex back-reference consistent.
    // `getPlayers()` is a top-level extension and is name-mangled in a production build, so the
    // revolutionary Player object is taken off one of its own units instead.
    rebelPlayer: () => {
      const grid = window.game.scenario.map.map;
      for (let r = 0; r < grid.length; r++) {
        for (let c = 0; c < grid[r].length; c++) {
          const u = grid[r][c] && grid[r][c].unit;
          if (u && u.owner === 0 && u.player) return u.player;
        }
      }
      return null;
    },
    moveRebelTo: (row, col) => {
      const grid = window.game.scenario.map.map;
      const rebels = window.__probe.rebelPlayer();
      let best = null;
      for (let r = 0; r < grid.length && !best; r++) {
        for (let c = 0; c < grid[r].length; c++) {
          const u = grid[r][c] && grid[r][c].unit;
          if (!u || u.destroyed) continue;
          // A LAND unit that can shoot; pick a loyalist one far from the compound so the
          // relocation itself cannot be what trips the trigger. Terrain 9/10/12/15 are
          // water/river/port/stream - a warship dropped on a town hex is not a fair stand-in
          // for the column the scenario expects to arrive.
          const water = [9, 10, 12, 15].includes(grid[r][c].terrain);
          if (!water && u.owner === 1 && window.__probe.dist(r, c, anchor.row, anchor.col) > 4) { best = { r, c, u }; break; }
        }
      }
      if (!best || !rebels) return null;
      grid[best.r][best.c].delUnit(best.u);
      best.u.owner = 0;
      best.u.player = rebels;
      grid[row][col].setUnit(best.u);
      return { from: [best.r, best.c], to: [row, col], eqid: best.u.eqid };
    },
    clearHex: (row, col) => {
      const hex = window.game.scenario.map.map[row][col];
      if (hex.unit) { hex.unit.destroyed = true; hex.delUnit(hex.unit); }
    },
    save: () => {
      window.game.state.save();
      const key = Object.keys(localStorage).find(k => k.startsWith('osada-scenario-'));
      return JSON.parse(localStorage.getItem(key));
    },
  };
}, { anchor: ANCHOR });

const results = [];
const ok = (name, cond, extra) => results.push([cond ? 'PASS' : 'FAIL', name, extra]);

// --- 1. nothing in the compound at load -------------------------------------------------------
const atLoad = await page.evaluate(() => ({
  prisoners: window.__probe.unitsWithEqid(218),
  compound: (() => { const h = window.game.scenario.map.map[10][34]; return { name: h.name, hasUnit: !!h.unit }; })(),
  saved: window.__probe.save().events,
}));
ok('no Prisoners unit anywhere on the map at load', atLoad.prisoners.length === 0, JSON.stringify(atLoad.prisoners));
ok('the compound hex (10,34) is empty', atLoad.compound.hasUnit === false, JSON.stringify(atLoad.compound));
ok('the compound hex is named as the detention compound', /Arrestlokal/.test(atLoad.compound.name), atLoad.compound.name);
ok('three events parsed out of the real n_kiel.xml', Array.isArray(atLoad.saved) && atLoad.saved.length === 3,
   JSON.stringify((atLoad.saved || []).map(e => e.id)));
ok('no event has fired yet', (atLoad.saved || []).every(e => e.fired === false));
const alarm = (atLoad.saved || []).find(e => e.id === 'kiel-prison-alarm');
ok('alarm event: proximity, anchor (10,34), radius 2, side 1, combat-only',
   !!alarm && alarm.kind === 'PROXIMITY' && alarm.row === 10 && alarm.col === 34 &&
   alarm.radius === 2 && alarm.side === 1 && alarm.combatOnly === true, JSON.stringify(alarm && {
     kind: alarm.kind, row: alarm.row, col: alarm.col, radius: alarm.radius, side: alarm.side, combatOnly: alarm.combatOnly }));
ok('standalone play (no campaign flag) selects the alarm branch, not the breakout branch',
   !!alarm && alarm.noneFlags.includes('sailors_liberated'));

/* The scenario's authored turn-2 reinforcement message is a modal, and an open modal sets
 * `uiMessageClicked = false`, which stops processTurn from running the AI at all. Clicking it away
 * is not cosmetic here: without it the AI silently never plays and every "did the AI react?"
 * assertion below passes or fails for the wrong reason. */
const dismissMessages = () => page.evaluate(() => {
  const ok = document.getElementById('uiokbut');
  if (ok && ok.offsetParent !== null) ok.click();
  window.game.uiMessageClicked = true;
});

/** Ends the revolutionary turn and waits for the loyalist AI to finish its own. */
async function playRound() {
  await dismissMessages();
  await page.evaluate(() => window.game.endTurn());
  for (let i = 0; i < 90; i++) {
    await sleep(1000);
    await dismissMessages();
    const back = await page.evaluate(() => {
      const p = window.game.scenario.map.currentPlayer;
      return !!p && p.id === 0;
    });
    if (back) return true;
  }
  return false;
}

// --- 2. three hexes out must NOT trip it, two hexes must --------------------------------------
// (11,31) is distance 3 from (10,34); (11,33) is distance 2.
const farPlaced = await page.evaluate(() => {
  window.__probe.clearHex(11, 31);
  const moved = window.__probe.moveRebelTo(11, 31);
  return { moved, dist: window.__probe.dist(11, 31, 10, 34) };
});
await playRound();
const far = await page.evaluate(() => window.__probe.unitsWithEqid(218));
ok('a revolutionary combat unit three hexes out does not raise the alarm',
   farPlaced.dist === 3 && far.length === 0, `dist=${farPlaced.dist} moved=${JSON.stringify(farPlaced.moved)} prisoners=${far.length}`);

// A fresh unit rather than the one placed at (11,31): that one just spent an AI turn standing
// next to the naval base and may well have been destroyed, which would make the next assertion
// pass or fail for the wrong reason.
await dismissMessages();
const near = await page.evaluate(() => {
  window.__probe.clearHex(11, 33);
  const moved = window.__probe.moveRebelTo(11, 33);
  window.game.endTurn();   // hand-off evaluation fires the alarm; read it BEFORE the AI shoots
  return { moved, dist: window.__probe.dist(11, 33, 10, 34), prisoners: window.__probe.unitsWithEqid(218) };
});
ok('closing to two hexes raises the alarm and puts the detainees on the map',
   near.dist === 2 && near.prisoners.length === 1,
   `dist=${near.dist} moved=${JSON.stringify(near.moved)} ${JSON.stringify(near.prisoners)}`);
ok('the detainees are strength 9, revolutionary-owned, temporary and out of the dossier',
   near.prisoners.length === 1 && near.prisoners[0].strength === 9 && near.prisoners[0].owner === 0 &&
   near.prisoners[0].temporary && near.prisoners[0].nodossier, JSON.stringify(near.prisoners[0]));

// --- 3. the loyalist AI attacks them, with no rules exception ---------------------------------
// endTurn() above already handed the turn to the AI. Because the alarm fired BEFORE
// scenario.endTurn() built the AI's plan, the garrison must engage on this very activation.
let afterAi = near.prisoners;
for (let i = 0; i < 90; i++) {
  await sleep(1000);
  await dismissMessages();
  const state = await page.evaluate(() => ({
    prisoners: window.__probe.unitsWithEqid(218),
    human: !!window.game.scenario.map.currentPlayer && window.game.scenario.map.currentPlayer.id === 0,
  }));
  afterAi = state.prisoners;
  if (state.human) break;
}
// Guarded on the column having existed, so this can never pass vacuously because nothing spawned.
ok('the loyalist garrison engages the column on its next activation (no combat-rule exception)',
   near.prisoners.length === 1 && (afterAi.length === 0 || afterAi[0].strength < 9),
   afterAi.length === 0 ? 'dispersed' : `strength ${afterAi.length ? afterAi[0].strength : 'never spawned'}`);

// An unarmed unit is the AI's preferred target - maximum kills, no return fire - so the column is
// normally broken up in that one activation. That is the designed outcome, not a bug: the player's
// window is the REST OF THE TURN the alarm went up in (leg 4). What must hold here is only that a
// rescue is then correctly unreachable rather than handing out the reward for a column that is gone.
const afterDispersal = await page.evaluate(() => {
  window.__probe.clearHex(11, 34);
  window.__probe.moveRebelTo(11, 34);
  window.game.endTurn();
  return { prisoners: window.__probe.unitsWithEqid(218), fired: window.__probe.save().events.filter(e => e.fired).map(e => e.id) };
});
if (afterAi.length === 0) {
  ok('once the column is dispersed, reaching the compound does NOT award the rescue',
     !afterDispersal.fired.includes('kiel-prisoners-rescued'), afterDispersal.fired.join(','));
} else {
  ok('reaching the compound while the column survives awards the rescue',
     afterDispersal.fired.includes('kiel-prisoners-rescued'), afterDispersal.fired.join(','));
}

// --- 4. the payoff path: reach the gate in the SAME turn the alarm goes up ---------------------
// A fresh battle, because the run above deliberately let the column be broken up. Here a
// revolutionary combat unit is already standing on a cleared hex adjacent to the compound when the
// alarm fires, which is the sequence a player who has cleared the approach actually plays.
await page.evaluate(() => { window.game.campaign = null; window.game.newScenario('n_kiel.xml', 'x'); });
for (let i = 0; i < 60; i++) {
  const loaded = await page.evaluate(() => !!(window.game.scenario && window.game.scenario.isLoaded));
  if (loaded) break;
  await sleep(300);
}
await sleep(1500);
await dismissMessages();

const rescue = await page.evaluate(() => {
  window.__probe.clearHex(11, 34);          // the watchtower the assault had to destroy first
  const moved = window.__probe.moveRebelTo(11, 34);
  window.game.endTurn();                    // alarm and rescue resolve in the same evaluation pass
  return {
    moved,
    prisoners: window.__probe.unitsWithEqid(218),
    freed: window.__probe.unitsWithEqid(46706),
    saved: window.__probe.save().events,
  };
});

ok('breaking through in time removes the column from the compound', rescue.prisoners.length === 0,
   JSON.stringify(rescue.prisoners));
const freed = (rescue.freed || []).filter(u => u.temporary && u.strength === 5);
ok('the freed sailors become a TEMPORARY strength-5 Volksmarine detachment (never a core formation)',
   freed.length === 1, JSON.stringify(rescue.freed));
const fired = (rescue.saved || []).filter(e => e.fired).map(e => e.id);
ok('the save records both the alarm and the rescue as fired',
   fired.includes('kiel-prison-alarm') && fired.includes('kiel-prisoners-rescued'), fired.join(','));
ok('the unfired breakout branch is still in the save with its definition intact',
   (rescue.saved || []).some(e => e.id === 'kiel-partial-breakout' && !e.fired && e.spawns.length === 1));

ok('no page errors', errs.length === 0, errs.slice(0, 3).join(' | '));

console.log('\n==== KIEL PRISONER SEQUENCE PROBE ====');
for (const [s, n, extra] of results) console.log(`${s}  ${n}${extra !== undefined ? '  [' + extra + ']' : ''}`);
console.log(`${results.filter(r => r[0] === 'PASS').length}/${results.filter(r => r[0] !== 'SKIP').length} passed`);
await browser.close();
server.close();
process.exit(results.some(r => r[0] === 'FAIL') ? 1 : 0);
