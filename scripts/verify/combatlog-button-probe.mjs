/**
 * Turn Report toggle: desktop face and centring.
 *
 * Two questions this answers with numbers rather than by reading CSS:
 *
 *  - which face the control wears. `#combatLogButton` carried a yellow `osada-menu` arrow from the
 *    beginning; 2026-09-04 replaced it outright with the map sprite, and 2026-09-05 restored the
 *    arrow for desktop while leaving the sprite to the phone bar. Exactly one of the two must be
 *    visible in each layout.
 *  - whether the button and the window it opens share a centreline. `#combatLog` is
 *    `position: fixed; left: 50%; margin-left: -400px`, i.e. pinned to the VIEWPORT centre, while
 *    the button is absolutely positioned inside `#statusbar`. Any padding, border or width the bar
 *    picks up moves one and not the other, which is the drift a user reported once already
 *    ("combatLog is righter than combatLogButton").
 *
 * Run: node scripts/verify/combatlog-button-probe.mjs
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
const PORT = parseInt(process.env.OSADA_PROBE_PORT || '8771', 10);
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

/** Everything worth knowing about the control, in one round trip. */
const measure = () => page.evaluate(() => {
  const btn = document.getElementById('combatLogButton');
  const log = document.getElementById('combatLog');
  const bar = document.getElementById('statusbar');
  const glyph = btn.querySelector('.osada-tb-combatlog__glyph');
  const ico = btn.querySelector('.osada-tb-combatlog__ico');
  const box = (el) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width), height: Math.round(r.height), centre: Math.round(r.left + r.width / 2) };
  };
  const shown = (el) => !!el && getComputedStyle(el).display !== 'none';
  return {
    layout: document.body.className.split(' ').filter((c) => c.startsWith('osada-layout-')).join(' '),
    viewportCentre: Math.round(window.innerWidth / 2),
    button: box(btn),
    log: box(log),
    bar: box(bar),
    glyphShown: shown(glyph),
    glyphText: glyph ? getComputedStyle(glyph, '::before').content : null,
    icoShown: shown(ico),
    // The class attribute survives the toggle: this is what uppercasing innerHTML used to destroy.
    icoClass: ico ? ico.className : null,
    selected: btn.hasAttribute('selected'),
  };
});

try {
  await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' });
  await sleep(1500);
  await page.evaluate(() => document.querySelector('.osada-tutorial__done')?.click());
  await page.evaluate((scenario) => {
    window.game.campaign = null;
    window.game.newScenario(scenario, 'x');
  }, SCENARIO);
  await sleep(3500);
  await page.evaluate(() => {
    const menu = document.getElementById('startmenu');
    if (menu) menu.style.display = 'none';
    document.getElementById('uiokbut')?.click();
  });
  await sleep(600);

  const closed = await measure();
  ok('desktop wears the arrow, not the sprite',
    closed.layout.includes('osada-layout-desktop') && closed.glyphShown && !closed.icoShown,
    JSON.stringify(closed));
  ok('the arrow is actually drawn (non-zero box)',
    closed.button.width > 0 && closed.button.height > 0, JSON.stringify(closed.button));
  ok('the button sits on the viewport centreline',
    Math.abs(closed.button.centre - closed.viewportCentre) <= 1,
    JSON.stringify({ button: closed.button.centre, viewport: closed.viewportCentre }));

  await page.evaluate(() => document.getElementById('combatLogButton').click());
  await sleep(250);
  const open = await measure();
  ok('opening the report flips the arrow', open.selected && open.glyphText !== closed.glyphText,
    JSON.stringify({ closed: closed.glyphText, open: open.glyphText, selected: open.selected }));
  ok('opening the report does not mangle the sprite class',
    open.icoClass === closed.icoClass && /osada-ico--map/.test(open.icoClass || ''),
    JSON.stringify({ before: closed.icoClass, after: open.icoClass }));
  // The window is measured HERE, not in the closed snapshot: a `display: none` element reports a
  // zero rect, so comparing centrelines before opening it compares the button against 0.
  ok('button and the open Turn Report share one centreline',
    Math.abs(open.button.centre - open.viewportCentre) <= 1 &&
      Math.abs(open.log.centre - open.viewportCentre) <= 1,
    JSON.stringify({ button: open.button.centre, log: open.log.centre, viewport: open.viewportCentre }));
  await page.evaluate(() => document.getElementById('combatLogButton').click());
  await sleep(200);

  // ---- phone keeps the sprite ----
  await page.setViewport({ width: 667, height: 375, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await sleep(500);
  const phone = await measure();
  ok('phone wears the sprite, not the arrow',
    phone.layout.includes('osada-layout-phone') && phone.icoShown && !phone.glyphShown,
    JSON.stringify(phone));
  ok('phone control keeps its touch target', phone.button.width >= 40 && phone.button.height >= 40,
    JSON.stringify(phone.button));

  ok('no runtime JS errors', errors.length === 0, errors.join(' | '));
} finally {
  await browser.close();
  server.close();
}

for (const [status, name, detail] of results) {
  console.log(`${status}  ${name}${detail ? `  -- ${detail}` : ''}`);
}
const failed = results.filter(([s]) => s === 'FAIL').length;
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed === 0 ? 0 : 1);
