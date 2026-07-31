/**
 * Mobile-viewport smoke test.
 *
 * Deliberately a SEPARATE task from `verify.mjs` rather than an extension of it: the production
 * smoke test asserts a desktop start-menu boot with zero console errors, and weakening it to also
 * emulate touch would blunt the one gate that catches desktop regressions.
 *
 * What this asserts is the mobile shell's structural contract, at the spec's minimum acceptance
 * viewport (667x375 landscape) with touch emulation on:
 *   - the layout controller classifies the device as a phone shell;
 *   - #game is the rectangle between the top bar and the bottom dock, not a window-sized box;
 *   - the sidebar is off-screen until the drawer is opened, and opening it does not move the map;
 *   - a synthesised pan scrolls the map and produces NO map click;
 *   - a synthesised pinch changes the zoom level and leaves it inside the 50-200% limits;
 *   - primary controls meet the 44px product minimum;
 *   - no runtime JS errors along the way.
 *
 * Chrome device emulation is not evidence for iOS Safari (spec §67.9) — real-device checks are
 * still required and are recorded in the PR, not here.
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
const PORT = parseInt(process.env.OSADA_MOBILE_PORT || '8766', 10);
const SCENARIO = process.env.OSADA_MOBILE_SCENARIO || 'drpzop01.xml';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.xml': 'application/xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.wav': 'audio/wav',
  '.mp3': 'audio/mpeg',
  '.ogg': 'audio/ogg',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const server = await new Promise((resolve) => {
  const s = http.createServer((req, res) => {
    const raw = decodeURIComponent(req.url.split('?')[0]);
    const filePath = path.join(DIST_DIR, raw === '/' ? 'index.html' : raw);
    fs.readFile(filePath, (err, data) => {
      if (err) {
        res.writeHead(404);
        res.end();
        return;
      }
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
// The spec's minimum acceptance viewport, with a coarse primary pointer.
await page.emulate({
  viewport: { width: 667, height: 375, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
  userAgent: 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
});

const errors = [];
page.on('pageerror', (e) => errors.push(String(e.message).slice(0, 200)));

try {
  await page.goto(`http://localhost:${PORT}/`, { waitUntil: 'networkidle2' });
  await sleep(1500);

  const startScreens = await page.evaluate(() => {
    const visible = (id) => getComputedStyle(document.getElementById(id)).display !== 'none';
    return { main: visible('smMain'), campaign: visible('smCamp'), scenario: visible('smScen') };
  });
  ok('only the active start-menu screen is visible',
    startScreens.main && !startScreens.campaign && !startScreens.scenario,
    JSON.stringify(startScreens));
  await page.evaluate(() => document.querySelector('.osada-tutorial__done')?.click());

  await page.evaluate((scenario) => {
    window.game.campaign = null;
    window.game.newScenario(scenario, 'x');
  }, SCENARIO);
  await sleep(3500);
  await page.evaluate(() => {
    const menu = document.getElementById('startmenu');
    if (menu) menu.style.display = 'none';
    const okBut = document.getElementById('uiokbut');
    if (okBut) okBut.click();
  });
  await sleep(600);

  // ---- layout mode + map viewport geometry ----
  const layout = await page.evaluate(() => {
    const cls = document.body.className;
    const game = document.getElementById('game').getBoundingClientRect();
    const bar = document.getElementById('statusbar').getBoundingClientRect();
    const sidebar = document.getElementById('osada-sidebar').getBoundingClientRect();
    return {
      cls,
      game: { top: Math.round(game.top), left: Math.round(game.left), width: Math.round(game.width), bottom: Math.round(game.bottom) },
      barBottom: Math.round(bar.bottom),
      sidebarLeft: Math.round(sidebar.left),
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
    };
  });
  ok('phone layout class applied', /osada-layout-phone/.test(layout.cls), layout.cls);
  ok('coarse-pointer class applied', /osada-input-coarse/.test(layout.cls));
  ok('map starts below the top bar', layout.game.top >= layout.barBottom, `game.top=${layout.game.top} bar.bottom=${layout.barBottom}`);
  ok('map does not run past the viewport bottom', layout.game.bottom <= layout.innerHeight + 1, `game.bottom=${layout.game.bottom}`);
  ok('map spans the full usable width', layout.game.width >= layout.innerWidth - 2, `game.width=${layout.game.width}`);
  ok('sidebar is off-screen until the drawer opens', layout.sidebarLeft >= layout.innerWidth - 2, `sidebar.left=${layout.sidebarLeft}`);

  // ---- drawer opens, and does NOT move the map ----
  const drawer = await page.evaluate(async () => {
    const game = document.getElementById('game');
    const before = { l: game.scrollLeft, t: game.scrollTop };
    document.getElementById('osadaDrawerBtn').click();
    await new Promise((r) => setTimeout(r, 350));
    const sidebar = document.getElementById('osada-sidebar').getBoundingClientRect();
    const after = { l: game.scrollLeft, t: game.scrollTop };
    const expanded = document.getElementById('osadaDrawerBtn').getAttribute('aria-expanded');
    const minimap = document.getElementById('osada-minimap');
    minimap.scrollIntoView({ block: 'center' });
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const mr = minimap.getBoundingClientRect();
    return {
      sidebarLeft: Math.round(sidebar.left), before, after, expanded,
      minimapX: mr.left + mr.width / 2, minimapY: mr.top + mr.height / 2,
      minimapWidth: Math.round(mr.width), minimapHeight: Math.round(mr.height),
    };
  });
  await page.touchscreen.tap(drawer.minimapX, drawer.minimapY);
  await sleep(80);
  const minimapCentre = await page.evaluate(({ width, height }) => {
    const game = document.getElementById('game');
    const source = document.getElementById('hexes');
    const zoom = parseInt(document.getElementById('osadaZoomPct').textContent, 10) / 100;
    const clientWidth = game.clientWidth / zoom;
    const clientHeight = game.clientHeight / zoom;
    const expectedLeft = Math.max(0, Math.min(source.width - clientWidth, source.width / 2 - clientWidth / 2)) * zoom;
    const expectedTop = Math.max(0, Math.min(source.height - clientHeight, source.height / 2 - clientHeight / 2)) * zoom;
    const result = {
      width,
      height,
      leftError: Math.round(Math.abs(game.scrollLeft - expectedLeft)),
      topError: Math.round(Math.abs(game.scrollTop - expectedTop)),
    };
    document.getElementById('osadaDrawerClose').click();
    return result;
  }, { width: drawer.minimapWidth, height: drawer.minimapHeight });
  await sleep(350);
  drawer.minimapCentre = minimapCentre;
  drawer.open = await page.evaluate(() => document.body.classList.contains('osada-drawer-open'));
  ok('drawer button opens the drawer', drawer.sidebarLeft < 667, `sidebar.left=${drawer.sidebarLeft}`);
  ok('drawer sets aria-expanded', drawer.expanded === 'true');
  ok('drawer does not shift the map', drawer.before.l === drawer.after.l && drawer.before.t === drawer.after.t);
  ok('rendered minimap centre maps to the map centre',
    drawer.minimapCentre.leftError <= 3 && drawer.minimapCentre.topError <= 3,
    JSON.stringify(drawer.minimapCentre));
  ok('drawer closes again', drawer.open === false);

  // ---- pan: scrolls the map, generates no click ----
  const pan = await page.evaluate(async () => {
    const game = document.getElementById('game');
    game.scrollLeft = 120;
    game.scrollTop = 60;
    const map = window.game.scenario.map;
    const selectedBefore = map.currentUnit ? map.currentUnit.id : null;
    const before = { l: game.scrollLeft, t: game.scrollTop };
    const cursor = document.getElementById('cursor');
    const send = (type, x, y, extra) => cursor.dispatchEvent(Object.assign(new PointerEvent(type, {
      pointerId: 1, pointerType: 'touch', clientX: x, clientY: y, buttons: type === 'pointerup' ? 0 : 1, bubbles: true, cancelable: true,
    }), extra || {}));
    send('pointerdown', 300, 200);
    for (let i = 1; i <= 6; i++) send('pointermove', 300 - i * 10, 200);
    send('pointerup', 240, 200);
    await new Promise((r) => setTimeout(r, 120));
    const selectedAfter = map.currentUnit ? map.currentUnit.id : null;
    return { before, after: { l: game.scrollLeft, t: game.scrollTop }, selectedBefore, selectedAfter };
  });
  ok('one-finger drag pans the map', pan.after.l !== pan.before.l, `${pan.before.l} -> ${pan.after.l}`);
  ok('a pan generates no map click', pan.selectedBefore === pan.selectedAfter, `${pan.selectedBefore} -> ${pan.selectedAfter}`);

  // ---- pinch: changes zoom, stays inside the limits ----
  // Zoom is read from the HUD percentage label, not from `window.uiSettings.zoomLevel`: Kotlin/JS
  // IR mangles property accessors, so the plain key name is `undefined` from JS (AGENTS.md).
  const pinch = await page.evaluate(async () => {
    const cursor = document.getElementById('cursor');
    const pct = () => parseInt((document.getElementById('osadaZoomPct') || {}).textContent || '0', 10);
    const before = pct();
    const send = (type, id, x, y) => cursor.dispatchEvent(new PointerEvent(type, {
      pointerId: id, pointerType: 'touch', clientX: x, clientY: y, buttons: type === 'pointerup' ? 0 : 1, bubbles: true, cancelable: true,
    }));
    send('pointerdown', 11, 250, 180);
    send('pointerdown', 12, 350, 180);
    for (let i = 1; i <= 8; i++) {
      send('pointermove', 11, 250 - i * 8, 180);
      send('pointermove', 12, 350 + i * 8, 180);
      await new Promise((r) => requestAnimationFrame(r));
    }
    send('pointerup', 11, 186, 180);
    send('pointerup', 12, 414, 180);
    await new Promise((r) => setTimeout(r, 250));
    return { before, after: pct() };
  });
  ok('pinch changes the map zoom', pinch.after > pinch.before, `${pinch.before}% -> ${pinch.after}%`);
  ok('zoom stays within 50-200%', pinch.after >= 50 && pinch.after <= 200, `${pinch.after}%`);

  // ---- touch targets ----
  const targets = await page.evaluate(() => {
    const ids = ['osadaEndTurn', 'osadaDrawerBtn', 'buy', 'options', 'zoom'];
    return ids.map((id) => {
      const el = document.getElementById(id);
      if (!el) return { id, missing: true };
      const r = el.getBoundingClientRect();
      return { id, w: Math.round(r.width), h: Math.round(r.height) };
    });
  });
  const tooSmall = targets.filter((t) => !t.missing && (t.h < 44 || t.w < 44));
  ok('primary controls are at least 44px', tooSmall.length === 0, JSON.stringify(tooSmall));
  ok('End Turn carries a visible text label', await page.evaluate(() => {
    const el = document.querySelector('#osadaEndTurn .osada-et__label');
    return !!el && el.textContent.trim().length > 0 && getComputedStyle(el).display !== 'none';
  }));

  // ---- compact equipment: list -> detail -> list, with selection retained ----
  const equipmentFlow = await page.evaluate(async () => {
    const wait = (ms) => new Promise((r) => setTimeout(r, ms));
    const visible = (id) => {
      const el = document.getElementById(id);
      return !!el && getComputedStyle(el).display !== 'none';
    };
    if (visible('equipment')) {
      document.getElementById('eqCloseBut').click();
      await wait(100);
    }
    document.getElementById('buy').click();
    await wait(150);
    document.getElementById('eqModeTab-purchase').click();
    await wait(150);
    const before = { list: visible('eqListPane'), detail: visible('eqDetailPane') };
    const card = document.querySelector('#eqUnitList .eqUnitBox');
    if (!card) return { before, card: false };
    card.click();
    await wait(150);
    const opened = {
      list: visible('eqListPane'),
      detail: visible('eqDetailPane'),
      back: visible('eqDetailBack'),
      selected: !!document.querySelector('#eqUnitList [selectedUnit]'),
    };
    document.getElementById('eqDetailBack').click();
    await wait(80);
    const returned = {
      list: visible('eqListPane'),
      detail: visible('eqDetailPane'),
      selected: !!document.querySelector('#eqUnitList [selectedUnit]'),
    };
    document.getElementById('eqCloseBut').click();
    return { before, opened, returned, card: true };
  });
  ok('compact equipment opens on the list',
    equipmentFlow.card && equipmentFlow.before.list && !equipmentFlow.before.detail,
    JSON.stringify(equipmentFlow));
  ok('equipment card opens a full-width detail screen',
    equipmentFlow.opened?.detail && !equipmentFlow.opened?.list && equipmentFlow.opened?.back,
    JSON.stringify(equipmentFlow.opened));
  ok('equipment Back restores the selected list item',
    equipmentFlow.returned?.list && !equipmentFlow.returned?.detail && equipmentFlow.returned?.selected,
    JSON.stringify(equipmentFlow.returned));

  ok('no runtime JS errors', errors.length === 0, errors.join(' | '));
} finally {
  await browser.close();
  server.close();
}

console.log('=== Mobile viewport smoke (667x375, coarse pointer) ===');
for (const [status, name, detail] of results) {
  console.log(`${status}  ${name}${detail ? `  [${detail}]` : ''}`);
}
const failed = results.filter((r) => r[0] === 'FAIL').length;
console.log(failed === 0 ? 'Overall: PASS' : `Overall: FAIL (${failed})`);
process.exit(failed === 0 ? 0 : 1);
