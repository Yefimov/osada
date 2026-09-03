// CSS migration Phase A: classifies every rule in ui.css as DEAD (selector never matches
// anything across the screen tour), LIVE (matches, and removing ui.css visibly changes computed
// style for at least one declared property), or SHADOWED (matches, but osada-theme.css/inline
// styles already own every declared property — removing ui.css changes nothing).
//
// Uses the browser's own CSSOM (document.styleSheets) to enumerate ui.css's parsed rules instead
// of hand-rolling a CSS parser — the file is minified into one line, real parsing handles that
// (and comma-separated selectors, pseudo-elements, etc.) for free.
//
// Usage: node ui-css-audit.mjs   (builds nothing; run jsBrowserDevelopmentExecutableDistribution
// first). Writes a JSON report to ui-css-audit-report.json in this directory.
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '..', '..', 'build', 'dist', 'js', 'developmentExecutable');
const PORT = 8821;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf' };
const sleep = ms => new Promise(r => setTimeout(r, ms));

const server = await new Promise(res => {
  const s = http.createServer((rq, rs) => {
    const raw = decodeURIComponent(rq.url.split('?')[0]);
    const fp = path.join(DIST, raw === '/' ? 'index.html' : raw);
    fs.readFile(fp, (e, d) => { if (e) { rs.writeHead(404); rs.end(); return; } rs.writeHead(200, { 'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream' }); rs.end(d); });
  });
  s.listen(PORT, () => res(s));
});
const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 1920, height: 1080 });
page.on('pageerror', e => console.log('PAGEERR', e.message.slice(0, 200)));

await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' });
await sleep(1500);

// ---- Enumerate ui.css's own parsed rules via CSSOM ----
const parsed = await page.evaluate(() => {
  const sheet = [...document.styleSheets].find(s => s.href && s.href.endsWith('/css/ui.css'));
  if (!sheet) return { error: 'ui.css stylesheet not found in document.styleSheets' };
  const rules = [];
  const walk = (ruleList) => {
    for (const r of ruleList) {
      if (r.type === CSSRule.STYLE_RULE) {
        const props = [];
        for (let i = 0; i < r.style.length; i++) props.push(r.style[i]);
        rules.push({ kind: 'style', selector: r.selectorText, props });
      } else if (r.type === CSSRule.MEDIA_RULE || r.type === CSSRule.SUPPORTS_RULE) {
        walk(r.cssRules);
      } else if (r.type === CSSRule.FONT_FACE_RULE) {
        rules.push({ kind: 'fontface', selector: null, props: [] });
      } else if (r.type === CSSRule.KEYFRAMES_RULE) {
        rules.push({ kind: 'keyframes', selector: r.name, props: [] });
      }
    }
  };
  walk(sheet.cssRules);
  return { count: rules.length, rules };
});
if (parsed.error) { console.error(parsed.error); process.exit(1); }
console.log(`ui.css parsed: ${parsed.count} rules`);

// One record per (selector) — comma-lists were already split into individual CSSStyleRule
// selectorText groups by the browser? No: selectorText keeps the full "a, b, c" text as one
// string per rule (matches source). Split here so each individual selector gets its own verdict.
const records = [];
for (const r of parsed.rules) {
  if (r.kind !== 'style') { records.push({ kind: r.kind, selector: r.selector, props: [], verdict: 'INFRA' }); continue; }
  for (const sel of r.selector.split(',').map(s => s.trim()).filter(Boolean)) {
    records.push({ kind: 'style', selector: sel, props: r.props, verdict: null, matchedAnywhere: false, diffAnywhere: false, screensMatched: [] });
  }
}
console.log(`individual selectors: ${records.filter(r => r.kind === 'style').length}`);

// ---- Screen tour: drive the app into each screen, snapshot DOM presence + computed-style diff ----
async function withUiCssToggled(enabled) {
  await page.evaluate((en) => {
    const link = [...document.querySelectorAll('link[rel=stylesheet]')].find(l => l.href.endsWith('/css/ui.css'));
    if (link) link.disabled = !en;
  }, enabled);
}

async function snapshotScreen(name) {
  // Pass 1: ui.css ON — record which selectors match anything at all.
  await withUiCssToggled(true);
  await sleep(80);
  const matchInfo = await page.evaluate((recs) => {
    return recs.map(r => {
      if (r.kind !== 'style') return null;
      let els;
      try { els = document.querySelectorAll(r.selector); } catch (e) { return { error: String(e).slice(0, 60) }; }
      if (els.length === 0) return { matched: false };
      // Snapshot the declared properties' computed values for up to 3 matched elements.
      const snaps = [...els].slice(0, 3).map(el => {
        const cs = getComputedStyle(el);
        return r.props.map(p => cs.getPropertyValue(p));
      });
      return { matched: true, snaps };
    });
  }, records);

  // Pass 2: ui.css OFF — re-snapshot the SAME selectors' matched elements' computed values.
  await withUiCssToggled(false);
  await sleep(80);
  const offInfo = await page.evaluate((recs) => {
    return recs.map(r => {
      if (r.kind !== 'style') return null;
      let els;
      try { els = document.querySelectorAll(r.selector); } catch (e) { return { error: true }; }
      if (els.length === 0) return { matched: false };
      const snaps = [...els].slice(0, 3).map(el => {
        const cs = getComputedStyle(el);
        return r.props.map(p => cs.getPropertyValue(p));
      });
      return { matched: true, snaps };
    });
  }, records);
  await withUiCssToggled(true);

  for (let i = 0; i < records.length; i++) {
    const rec = records[i];
    if (rec.kind !== 'style') continue;
    const on = matchInfo[i], off = offInfo[i];
    if (!on || on.error) continue;
    if (on.matched) {
      rec.matchedAnywhere = true;
      if (!rec.screensMatched.includes(name)) rec.screensMatched.push(name);
      // Diff: any snapshot/property differs between ui.css ON and OFF?
      if (off && off.matched) {
        for (let s = 0; s < on.snaps.length; s++) {
          for (let p = 0; p < on.snaps[s].length; p++) {
            if (on.snaps[s][p] !== (off.snaps[s] || [])[p]) { rec.diffAnywhere = true; break; }
          }
          if (rec.diffAnywhere) break;
        }
      } else {
        // Matched with ui.css on, but the exact same selector found NOTHING with it off — that's
        // impossible for a plain style rule (ui.css doesn't create/destroy elements), so treat as
        // a diff to be safe (something about layout-dependent selector matching changed).
        rec.diffAnywhere = true;
      }
    }
  }
  console.log(`  [${name}] snapshot done`);
}

// --- Drive each screen ---
console.log('Screen: main menu');
await snapshotScreen('main-menu');

console.log('Screen: settings');
await page.evaluate(() => { document.getElementById('settings')?.click(); });
await sleep(300);
await snapshotScreen('settings');

console.log('Screen: campaign browser');
await page.evaluate(() => { document.getElementById('smSetOkBut')?.click(); });
await sleep(200);
await page.evaluate(() => { document.getElementById('newcampaign')?.click(); });
await sleep(300);
await snapshotScreen('campaign-browser');

console.log('Screen: scenario browser');
await page.evaluate(() => { document.getElementById('smMain')?.style && (document.getElementById('smMain').style.display=''); document.getElementById('smCamp')?.style && (document.getElementById('smCamp').style.display='none'); document.getElementById('newscenario')?.click(); });
await sleep(300);
await snapshotScreen('scenario-browser');

console.log('Screen: save/load');
await page.evaluate(() => { document.getElementById('smScen')?.style && (document.getElementById('smScen').style.display='none'); document.getElementById('smMain')?.style && (document.getElementById('smMain').style.display=''); document.getElementById('saveload')?.click(); });
await sleep(300);
await snapshotScreen('save-load');

console.log('Screen: in-game HUD (drpzop01)');
await page.evaluate(() => { window.game.campaign = null; window.game.newScenario('drpzop01.xml', 'x'); });
await sleep(3000);
await page.evaluate(() => { document.getElementById('startmenu').style.display = 'none'; const b = document.getElementById('uiokbut'); if (b) b.click(); });
await sleep(500);
await snapshotScreen('in-game-hud');

console.log('Screen: equipment window (reserve tab)');
await page.evaluate(() => { document.getElementById('buy')?.click(); });
await sleep(400);
await snapshotScreen('equipment-reserve');

console.log('Screen: equipment window (upgrade tab)');
await page.evaluate(() => {
  const map = window.game.scenario.map;
  const unit = map.getUnits().find(u => u.player && u.player.id === map.currentPlayer.id);
  if (unit) window.game.ui.uiUnitSelect(unit);
});
await sleep(300);
await snapshotScreen('equipment-upgrade');

console.log('Screen: turn report');
await page.evaluate(() => { document.getElementById('combatLogButton')?.click(); });
await sleep(400);
await snapshotScreen('turn-report');

console.log('Screen: message dialog');
await page.evaluate(() => {
  const el = document.getElementById('ui-message');
  if (el) el.style.display = 'block';
});
await sleep(200);
await snapshotScreen('message-dialog');

await browser.close();
server.close();

// ---- Classify ----
for (const rec of records) {
  if (rec.kind !== 'style') continue;
  if (!rec.matchedAnywhere) rec.verdict = 'DEAD';
  else if (rec.diffAnywhere) rec.verdict = 'LIVE';
  else rec.verdict = 'SHADOWED';
}

const counts = {};
for (const r of records) counts[r.verdict] = (counts[r.verdict] || 0) + 1;
console.log('\n=== VERDICT COUNTS ===');
console.log(JSON.stringify(counts, null, 1));

const outPath = path.join(__dirname, 'ui-css-audit-report.json');
fs.writeFileSync(outPath, JSON.stringify(records, null, 1), 'utf-8');
console.log(`\nFull report -> ${outPath}`);

console.log('\n=== LIVE selectors (must be ported to base.css) ===');
records.filter(r => r.verdict === 'LIVE').forEach(r => console.log(' ', r.selector, r.props.join(',')));

console.log('\n=== INFRA (font-face/keyframes, always keep) ===');
records.filter(r => r.verdict === 'INFRA').forEach(r => console.log(' ', r.kind, r.selector || ''));
