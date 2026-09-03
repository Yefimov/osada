import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '..', '..', 'build', 'dist', 'js', 'developmentExecutable');
const PORT = 8837;
const MIME = {'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const server = await new Promise(res => {
  const s = http.createServer((rq, rs) => {
    const raw = decodeURIComponent(rq.url.split('?')[0]);
    const fp = path.join(DIST, raw === '/' ? 'index.html' : raw);
    fs.readFile(fp, (e, d) => {
      if (e) { rs.writeHead(404); rs.end(); return; }
      rs.writeHead(200, {'Content-Type': MIME[path.extname(fp).toLowerCase()] || 'application/octet-stream'});
      rs.end(d);
    });
  });
  s.listen(PORT, () => res(s));
});
const browser = await puppeteer.launch({executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox']});
const page = await browser.newPage();
await page.setViewport({width: 1920, height: 1080});
const errs = [];
page.on('pageerror', e => errs.push(e.message.slice(0, 200)));
const results = [];
const ok = (n, c, extra) => results.push([c ? 'PASS' : 'FAIL', n, extra === undefined ? '' : JSON.stringify(extra)]);

await page.goto('http://localhost:' + PORT + '/', {waitUntil: 'networkidle2'});
await sleep(1800);
await page.evaluate(() => { window.game.campaign = null; window.game.newScenario('n_kiel.xml', 'x'); });
await sleep(3500);
await page.evaluate(() => {
  const sm = document.getElementById('startmenu'); if (sm) sm.style.display = 'none';
  const b = document.getElementById('uiokbut'); if (b) b.click();
});
await sleep(900);

// ---------- 1. victory deadlines: gone from the rail, present as a hover panel ----------
const rail = await page.evaluate(() => ({
  tierRows: document.querySelectorAll('#osadaObjectives .osada-obj-tier').length,
  endRows: document.querySelectorAll('#osadaObjectives .osada-obj-end').length,
  turnField: !!document.getElementById('osadaTurnField'),
}));
ok('no victory-tier rows left in the rail', rail.tierRows === 0);
ok('no end-state rows left in the rail', rail.endRows === 0);
ok('turn field has an id to anchor the panel', rail.turnField);

const tip = await page.evaluate(async () => {
  document.getElementById('osadaTurnField').dispatchEvent(new MouseEvent('mouseenter'));
  await new Promise(r => setTimeout(r, 150));
  const t = document.getElementById('osadaVictoryTip');
  if (!t) return {shown: false};
  const r = t.getBoundingClientRect();
  return {
    shown: getComputedStyle(t).display === 'block',
    title: t.querySelector('.osada-wtip__title').textContent,
    story: t.querySelector('.osada-wtip__story').textContent,
    lines: [...t.querySelectorAll('.osada-wtip__line')].map(l => ({text: l.textContent, color: getComputedStyle(l).color})),
    rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width)],
  };
});
ok('turn hover opens the victory-deadline panel', tip.shown);
ok('panel titles the turn', /\d+.+\d+/.test(tip.title || ''), tip.title);
ok('panel has one line per victory tier', (tip.lines || []).length === 3, (tip.lines || []).map(l => l.text));
ok('reachable tiers are green', (tip.lines || []).every(l => l.color === 'rgb(143, 206, 122)'), (tip.lines || []).map(l => l.color));
ok('panel is on screen', tip.rect && tip.rect[0] >= 0 && tip.rect[1] > 0, tip.rect);
ok('panel says how long is left', /\d/.test(tip.story || ''), tip.story);

const missed = await page.evaluate(async () => {
  window.game.scenario.map.turn = 99;
  document.getElementById('osadaTurnField').dispatchEvent(new MouseEvent('mouseenter'));
  await new Promise(r => setTimeout(r, 150));
  const t = document.getElementById('osadaVictoryTip');
  const lines = [...t.querySelectorAll('.osada-wtip__line')].map(l => ({text: l.textContent, color: getComputedStyle(l).color}));
  window.game.scenario.map.turn = 1;
  return lines;
});
ok('a passed deadline turns red', missed.every(l => l.color === 'rgb(224, 122, 114)'), missed.map(l => l.color));

// ---------- 2. optional capture points fold ----------
const fold = await page.evaluate(() => {
  const pick = () => [...document.querySelectorAll('#osadaObjectives .osada-obj-section')].find(s => s.classList.contains('osada-obj-section--fold'));
  const h = pick();
  if (!h) return {found: false};
  const heading = h.textContent;
  const before = document.querySelectorAll('#osadaObjectives .osada-obj').length;
  h.click();
  const after = document.querySelectorAll('#osadaObjectives .osada-obj').length;
  pick().click();
  return {found: true, heading, before, after};
});
ok('optional section is a fold carrying a count', fold.found && /\d+\/\d+/.test(fold.heading || ''), fold.heading);
ok('folding changes how many rows the rail draws', fold.found && fold.before !== fold.after, [fold.before, fold.after]);

// ---------- 3. equipment detail: marks centred, extended badges muted ----------
const marks = await page.evaluate(async () => {
  document.getElementById('buy').click();
  await new Promise(r => setTimeout(r, 800));
  const rows = [...document.querySelectorAll('.eqUnitBox')];
  let best = null;
  let primarySeen = null;
  for (const row of rows.slice(0, 120)) {
    row.click();
    await new Promise(r => setTimeout(r, 90));
    const m = document.querySelector('.osada-eqd-marks');
    if (!m || m.children.length < 2) continue;
    const ext = [...m.children].filter(c => c.className.includes('--ability'));
    const prim = [...m.children].filter(c => !c.className.includes('--ability'));
    const name = document.querySelector('.osada-eqd-name').getBoundingClientRect();
    const mr = m.getBoundingClientRect();
    const prose = document.querySelector('.osada-eqd-mechanics') || document.querySelector('.osada-eqd-desc');
    const card = document.getElementById('osadaUcMarkings');
    const cand = {
      found: true, display: getComputedStyle(m).display, badges: m.children.length,
      nameCenter: Math.round(name.left + name.width / 2), markCenter: Math.round(mr.left + mr.width / 2),
      prose: prose ? getComputedStyle(prose).userSelect : null,
      extended: ext.length, primary: prim.length,
      extStyle: ext.length ? [getComputedStyle(ext[0]).borderTopStyle, getComputedStyle(ext[0]).color] : null,
      primStyle: prim.length ? getComputedStyle(prim[0]).color : null,
      cardExtended: card ? [...card.children].filter(c => c.className.includes('--ability')).length : null,
      cardBadges: card ? card.children.length : null,
    };
    // Track the brass colour from ANY record that has a primary badge: the record with the most
    // extended badges need not have one, and that is what the assertion is about.
    if (cand.primStyle && !primarySeen) primarySeen = cand.primStyle;
    if (!best || cand.extended > best.extended) best = cand;
    if (cand.extended >= 2 && primarySeen) break;
  }
  return Object.assign(best || {found: false}, {primarySeen});
});
ok('found an equipment record with badges', marks.found);
ok('marks row is a block-level flex row', marks.display === 'flex', marks.display);
ok('marks row is centred on the name', Math.abs(marks.nameCenter - marks.markCenter) <= 2, [marks.nameCenter, marks.markCenter]);
ok('purchase bay still shows extended badges', marks.extended > 0, [marks.primary, marks.extended]);
ok('extended badges are muted grey + dashed', marks.extStyle && marks.extStyle[0] === 'dashed' && marks.extStyle[1] === 'rgb(139, 139, 139)', marks.extStyle);
ok('primary badges stay brass', marks.primarySeen === 'rgb(224, 183, 84)', marks.primarySeen);
ok('the unit card carries NO extended badges', marks.cardExtended === 0, [marks.cardBadges, marks.cardExtended]);

// ---------- 4. selectable prose ----------
ok('equipment prose is selectable', marks.prose === 'text', marks.prose);

const briefingSelectable = await page.evaluate(() => {
  const probe = document.createElement('section');
  probe.className = 'osada-briefing__orders';
  const p = document.createElement('p');
  p.className = 'osada-briefing__order-text';
  probe.appendChild(p);
  document.body.appendChild(probe);
  const v = getComputedStyle(p).userSelect;
  probe.remove();
  return v;
});
ok('briefing orders prose is selectable', briefingSelectable === 'text', briefingSelectable);

// ---------- 5/6. i18n hygiene ----------
const bundleChecks = await page.evaluate(async () => {
  const bundle = await (await fetch('i18n/en/ui.json')).json();
  return {
    og: Object.entries(bundle)
      .filter(([k, v]) => /^equipment\.(ability|mechanics|buy_blocked)\./.test(k) && typeof v === 'string' && /Open General/i.test(v))
      .map(([k]) => k),
    missing: ['cannot_attack_naval', 'cant_buy', 'no_ai_buy', 'no_prototype', 'air_transportable']
      .filter(k => !bundle['equipment.ability.' + k]),
  };
});
ok('no "Open General" left in ability/mechanics prose', bundleChecks.og.length === 0, bundleChecks.og);
ok('every ability badge has a description', bundleChecks.missing.length === 0, bundleChecks.missing);

// ---------- 7. "All stats" above the action strip ----------
await page.evaluate(() => { const b = document.getElementById('eqExitBut'); if (b) b.click(); });
await sleep(500);
// Selected through the HUD's own "next ready formation" button: `GameMap.units` is `internal`
// and mangled out of reach from here, and the button is the path a player actually takes.
await page.evaluate(() => { document.getElementById('osadaNavNext').click(); });
await sleep(800);
const geom = await page.evaluate(() => {
  const side = document.getElementById('uc-side');
  const zone = document.getElementById('osada-bottomzone');
  if (!side) return {found: false};
  const e = document.getElementById('uc-expand').getBoundingClientRect();
  const a = document.getElementById('uc-actions').getBoundingClientRect();
  const s = side.getBoundingClientRect();
  const card = document.getElementById('unit-info').getBoundingClientRect();
  return {found: true, zoneHeight: getComputedStyle(zone).height, dir: getComputedStyle(side).flexDirection,
          expandBottom: Math.round(e.bottom), actionsTop: Math.round(a.top), cardHeight: Math.round(card.height),
          sideInsideCard: s.top >= card.top - 1 && s.bottom <= card.bottom + 1};
});
if (geom.found) {
  ok('right column stacks vertically', geom.dir === 'column', geom.dir);
  ok('"All stats" sits ABOVE the action strip', geom.expandBottom <= geom.actionsTop, [geom.expandBottom, geom.actionsTop]);
  ok('the column fits inside the card', geom.sideInsideCard, [geom.cardHeight]);
  ok('bottom band grew to 136px', geom.zoneHeight === '136px', geom.zoneHeight);
} else {
  results.push(['SKIP', 'unit card geometry (card not shown)', '']);
}

// ---------- 8. the CAMPAIGN briefing renders its end-of-mission conditions ----------
// This is the path the standalone scenario above never touches: `addCampaignEndStateSection`
// only does anything when a campaign is loaded, and it reads `getCurrentScenarioActions()`,
// which is a raw JS array. n_kiel is campaign 8 (November Revolution) scenario 0 and authors
// six such rules, so a crash here is a crash on the very first briefing of that campaign.
const campaign = await page.evaluate(async () => {
  const errors = [];
  const onErr = e => errors.push(String(e.message || e).slice(0, 160));
  window.addEventListener('error', onErr);
  window.game.newCampaign(8, 1);
  await new Promise(r => setTimeout(r, 7000));
  // Click through the dialogue until the ORDERS sheet is up. The panel click alone is not
  // enough: this conversation stops on a required decision, so the choice button has to be
  // pressed too or the loop spins on the same line forever.
  const orders = () => document.querySelector('.osada-briefing__orders');
  for (let i = 0; i < 80; i++) {
    if (orders() && getComputedStyle(orders()).display !== 'none') break;
    const panel = document.querySelector('.osada-dialogue');
    if (panel) panel.click();
    const choice = document.querySelector('.osada-dialogue__choice');
    if (choice) choice.click();
    await new Promise(r => setTimeout(r, 150));
  }
  window.removeEventListener('error', onErr);
  const rows = [...document.querySelectorAll('.osada-briefing__endstate-row')].map(r => ({
    name: r.querySelector('.osada-briefing__endstate-name').textContent,
    state: r.querySelector('.osada-briefing__endstate-state').textContent,
    border: getComputedStyle(r).borderLeftColor,
  }));
  const heading = [...document.querySelectorAll('.osada-briefing__order-heading')].map(h => h.textContent);
  return {errors, rows, heading, ordersVisible: !!document.querySelector('.osada-briefing__orders')};
});
ok('campaign briefing renders without throwing', campaign.errors.length === 0, campaign.errors);
ok('campaign-conditions section is gone', !campaign.heading.some(h => /CAMPAIGN|КАМПАНИИ/i.test(h)), campaign.heading);
ok('no end-state rows anywhere', campaign.rows.length === 0, campaign.rows.length);

// The whole sheet must speak ONE language.
const cyr = campaign.heading.filter(h => /[Ѐ-ӿ]/.test(h)).length;
ok('every orders heading is in the same language', cyr === campaign.heading.length, campaign.heading);

// ---------- 9. the two-column orders grid comes out even ----------
const grid = await page.evaluate(() => {
  const content = document.querySelector('.osada-briefing__orders-content');
  const kids = [...content.children];
  const runs = [];
  let run = 0;
  for (const k of kids) {
    if (k.classList.contains('osada-briefing__order-section--wide')) { if (run) runs.push(run); run = 0; }
    else run++;
  }
  if (run) runs.push(run);
  // A hole is a narrow card with nothing beside it: last in an odd run.
  return {runs, sections: kids.length,
          tops: kids.map(k => Math.round(k.getBoundingClientRect().top)),
          rights: kids.map(k => Math.round(k.getBoundingClientRect().right)),
          contentRight: Math.round(content.getBoundingClientRect().right)};
});
ok('no narrow section is left unpaired', grid.runs.every(n => n % 2 === 0), grid.runs);
// Every grid row must reach the right edge — that is the visual "hole" restated geometrically.
const rowsReachEdge = await page.evaluate(() => {
  const content = document.querySelector('.osada-briefing__orders-content');
  const byTop = new Map();
  for (const k of content.children) {
    const r = k.getBoundingClientRect();
    const t = Math.round(r.top);
    byTop.set(t, Math.max(byTop.get(t) || 0, Math.round(r.right)));
  }
  const edge = Math.round(content.getBoundingClientRect().right);
  return [...byTop.entries()].filter(([, right]) => Math.abs(right - edge) > 2);
});
ok('every row fills the sheet width', rowsReachEdge.length === 0, rowsReachEdge);

// ---------- 10. clicking an objective marks its hex ----------
await page.evaluate(() => { const b = document.querySelector('.osada-briefing__button--primary'); if (b) b.click(); });
await sleep(2500);
const focus = await page.evaluate(async () => {
  const row = document.querySelector('#osadaObjectives .osada-obj');
  if (!row) return {found: false};
  row.click();
  await new Promise(r => setTimeout(r, 300));
  const lit = document.querySelectorAll('#osadaObjectives .osada-obj--focused').length;
  const litRow = document.querySelector('#osadaObjectives .osada-obj--focused');
  const bg = litRow ? getComputedStyle(litRow).backgroundColor : null;
  // click it again -> mark clears
  litRow.click();
  await new Promise(r => setTimeout(r, 300));
  const after = document.querySelectorAll('#osadaObjectives .osada-obj--focused').length;
  return {found: true, lit, after, bg};
});
ok('clicking an objective lights exactly one row', focus.found && focus.lit === 1, focus);
ok('the lit row is green', /^rgba?\(\s*80,\s*190,\s*110/.test(focus.bg || ''), focus.bg);
ok('clicking it again clears the mark', focus.after === 0, focus.after);

// ---------- 11. All Stats carries the full ability reference ----------
const allStats = await page.evaluate(async () => {
  document.getElementById('osadaNavNext').click();
  await new Promise(r => setTimeout(r, 500));
  document.getElementById('uc-expand').click();
  await new Promise(r => setTimeout(r, 250));
  const box = document.getElementById('osadaUcAbilities');
  const container = document.getElementById('statsRowContainer');
  const detail = document.getElementById('osadaFormationDetail');
  if (!box) return {found: false, containerShown: container ? getComputedStyle(container).display : null};
  const kids = [...container.children];
  return {
    found: true,
    rows: box.querySelectorAll('.osada-uc-abilities__row').length,
    headline: box.querySelector('.osada-uc-abilities__headline').textContent,
    everyRowHasText: [...box.querySelectorAll('.osada-uc-abilities__text')].every(t => t.textContent.trim().length > 3),
    aboveFormationDetail: !detail || kids.indexOf(box) < kids.indexOf(detail),
  };
});
if (allStats.found) {
  ok('All Stats lists the abilities in full', allStats.rows > 0, allStats.rows);
  ok('every listed ability has its sentence', allStats.everyRowHasText);
  ok('the list sits above the formation record', allStats.aboveFormationDetail);
  ok('the list is headed with a count', /\d/.test(allStats.headline), allStats.headline);
} else {
  results.push(['SKIP', 'All Stats ability list (selected formation carries no extended ability)', JSON.stringify(allStats)]);
}

await page.screenshot({path: path.join(__dirname, 'design-2026-09-02.png')});
ok('no page errors', errs.length === 0, errs);

console.log(results.map(r => r[0] + '  ' + r[1] + (r[2] ? '   ' + r[2] : '')).join('\n'));
const failed = results.filter(r => r[0] === 'FAIL').length;
await browser.close();
server.close();
process.exit(failed ? 1 : 0);
