/**
 * Two of the four faults in the 2026-09-05 Headquarters screenshot are pure stylesheet bugs, and
 * both are invisible to a unit test because they only exist once the browser has cascaded the real
 * sheet. This measures them, plus the scenario loading curtain, in a real Chrome.
 *
 *  - the row portrait showed the top-left corner of the bust at natural size. This sheet has no
 *    global `border-box` and no ordering discipline: `.osada-hero-rosterrow-portrait`'s `background:`
 *    SHORTHAND sits further down the file than `.osada-portrait-photo` and reset background-size
 *    back to `auto`. The dossier header's rule sits ABOVE it, which is why only the roster broke.
 *  - "Найти" sat flush against the panel edge: `width: 100%` plus 12px padding and a 1px border made
 *    the row 26px wider than the list holding it, so it overhung on the right.
 *
 * The row is built here from the classes CommanderRosterPresenter.renderRow actually assigns, so
 * what is measured is the shipped cascade rather than a paraphrase of it.
 *
 * The other two faults (raw `hero.roster.tab.*` keys, and "Капитан Captain ..." doubling) are
 * covered by HeroDossierTest and by the authored roster no longer carrying titles in `name`.
 *
 * Run: node scripts/verify/hero-roster-probe.mjs
 */
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';
import { getChromePath } from 'chrome-launcher';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..', '..');
const DIST_DIR = process.env.OSADA_DIST_DIR
  ? path.resolve(process.env.OSADA_DIST_DIR)
  : path.resolve(ROOT_DIR, 'build', 'dist', 'js', 'productionExecutable');
const PORT = parseInt(process.env.OSADA_PROBE_PORT || '8773', 10);
const SCENARIO = process.env.OSADA_PROBE_SCENARIO || 'drpzop01.xml';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.xml': 'application/xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.ttf': 'font/ttf', '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg',
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const server = await new Promise((resolve) => {
  const s = http.createServer((req, res) => {
    const raw = decodeURIComponent(req.url.split('?')[0]);
    const filePath = path.join(DIST_DIR, raw === '/' ? 'index.html' : raw);
    fs.readFile(filePath, (err, data) => {
      if (err) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream' });
      res.end(data);
    });
  });
  s.listen(PORT, () => resolve(s));
});

const results = [];
const ok = (name, pass, detail) => results.push([pass ? 'PASS' : 'FAIL', name, detail ?? '']);

const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: 'new', args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));

try {
  await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' });
  await sleep(1500);
  await page.evaluate(() => document.querySelector('.osada-tutorial__done')?.click());

  // ---- curtain: up the moment the next battle is asked for ----
  await page.evaluate((scenario) => {
    window.game.campaign = null;
    window.game.newScenario(scenario, 'x');
  }, SCENARIO);
  const curtain = await page.evaluate(() => {
    const el = document.getElementById('scenarioLoadingCurtain');
    if (!el) return { present: false };
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return {
      present: true,
      display: cs.display,
      zIndex: cs.zIndex,
      covers: Math.round(r.width) >= window.innerWidth && Math.round(r.height) >= window.innerHeight,
      text: (el.textContent || '').trim(),
      hasImage: /campaign_screen_background/.test(cs.backgroundImage),
    };
  });
  ok('curtain is up while the next scenario loads',
    curtain.present && curtain.display === 'flex' && curtain.covers, JSON.stringify(curtain));
  ok('curtain wears the menu picture and a real caption',
    !!curtain.hasImage && !!curtain.text && !curtain.text.includes('game.loading'),
    JSON.stringify({ text: curtain.text, hasImage: curtain.hasImage }));
  ok('curtain sits above the map but below the campaign briefing (1200)',
    Number(curtain.zIndex) > 300 && Number(curtain.zIndex) < 1200, String(curtain.zIndex));

  await sleep(4000);
  await page.evaluate(() => {
    const menu = document.getElementById('startmenu');
    if (menu) menu.style.display = 'none';
    document.getElementById('uiokbut')?.click();
  });
  await sleep(1000);
  const down = await page.evaluate(() =>
    getComputedStyle(document.getElementById('scenarioLoadingCurtain')).display);
  ok('curtain comes down once the new map is drawn', down === 'none', down);

  // ---- roster row geometry, against the shipped sheet ----
  const row = await page.evaluate(() => {
    const panel = document.createElement('div');
    panel.className = 'osada-hero-roster';
    // The list body the presenter renders rows into; 720px panel, 16px side padding.
    const body = document.createElement('div');
    body.style.cssText = 'width:688px;padding:0;';
    panel.appendChild(body);
    document.body.appendChild(panel);

    const card = document.createElement('div');
    card.className = 'osada-hero-rosterrow';
    body.appendChild(card);

    const portrait = document.createElement('div');
    portrait.className = 'osada-hero-rosterrow-portrait osada-renown--distinguished osada-portrait-photo';
    portrait.style.backgroundImage = "url('data:image/gif;base64,R0lGODlhAQABAAAAACw=')";
    card.appendChild(portrait);

    const copy = document.createElement('div');
    copy.className = 'osada-hero-rosterrow-copy';
    copy.innerHTML = '<div class="osada-hero-rosterrow-name">Капитан Alexei Serebryakov</div>' +
      '<div class="osada-hero-rosterrow-sub">T-26 M33</div>';
    card.appendChild(copy);

    const locate = document.createElement('button');
    locate.className = 'osada-hero-locate';
    locate.textContent = 'Найти';
    card.appendChild(locate);

    const cardBox = card.getBoundingClientRect();
    const bodyBox = body.getBoundingClientRect();
    const btnBox = locate.getBoundingClientRect();
    const pcs = getComputedStyle(portrait);
    const out = {
      boxSizing: getComputedStyle(card).boxSizing,
      overhangRight: Math.round(cardBox.right - bodyBox.right),
      buttonInset: Math.round(cardBox.right - btnBox.right),
      backgroundSize: pcs.backgroundSize,
      backgroundPosition: pcs.backgroundPosition,
      backgroundRepeat: pcs.backgroundRepeat,
      // The dossier header's portrait, which was already correct — it must not have regressed.
      dossier: (() => {
        const d = document.createElement('div');
        d.className = 'osada-hero-portrait osada-portrait-photo';
        d.style.backgroundImage = "url('data:image/gif;base64,R0lGODlhAQABAAAAACw=')";
        panel.appendChild(d);
        const cs = getComputedStyle(d);
        return { size: cs.backgroundSize, position: cs.backgroundPosition };
      })(),
    };
    panel.remove();
    return out;
  });

  ok('roster row is border-box and no longer overhangs its list',
    row.boxSizing === 'border-box' && row.overhangRight <= 0, JSON.stringify(row));
  ok('"Найти" keeps the same inset the portrait has on the left',
    row.buttonInset >= 12, String(row.buttonInset));
  ok('painted row portrait covers its frame instead of showing the corner',
    row.backgroundSize === 'cover' && row.backgroundRepeat === 'no-repeat',
    JSON.stringify({ size: row.backgroundSize, position: row.backgroundPosition }));
  ok('dossier header portrait still covers (no regression)',
    row.dossier.size === 'cover', JSON.stringify(row.dossier));

  ok('no runtime JS errors', errors.length === 0, errors.join(' | '));
} finally {
  await browser.close();
  server.close();
}

for (const [status, name, detail] of results) console.log(`${status}  ${name}${detail ? `  -- ${detail}` : ''}`);
const failed = results.filter(([s]) => s === 'FAIL').length;
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed === 0 ? 0 : 1);
