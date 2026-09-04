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
 *   - the phone HUD uses full-size report/reserves controls instead of clipped status text;
 *   - the passive lower rail yields to the unit card and keeps turn/weather/hex context;
 *   - campaign utility controls fit, retain accessible labels and meet the 44px minimum;
 *   - unit actions stay inside the card and the stats popover dismisses outside itself;
 *   - the drawer's X and log remain usable at the minimum landscape height;
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

  // ---- start menu + campaign browser reachability in both phone orientations ----
  const menuLandscape = await page.evaluate(() => {
    const scroller = document.getElementById('smButtons');
    scroller.scrollTop = 0;
    const taglineRect = document.getElementById('smLogoText').getBoundingClientRect();
    const firstVisible = [...scroller.children].find((el) => getComputedStyle(el).display !== 'none');
    const firstRect = firstVisible.getBoundingClientRect();
    const last = scroller.lastElementChild;
    scroller.scrollTop = scroller.scrollHeight;
    const r = last.getBoundingClientRect();
    return {
      overflowY: getComputedStyle(scroller).overflowY,
      canScroll: scroller.scrollHeight > scroller.clientHeight,
      taglineBottom: Math.round(taglineRect.bottom), firstTop: Math.round(firstRect.top),
      lastTop: Math.round(r.top), lastBottom: Math.round(r.bottom), viewport: window.innerHeight,
    };
  });
  ok('landscape main menu keeps its final command reachable',
    menuLandscape.lastTop >= 0 && menuLandscape.lastBottom <= menuLandscape.viewport + 1,
    JSON.stringify(menuLandscape));
  ok('landscape main-menu commands start below the tagline',
    menuLandscape.firstTop >= menuLandscape.taglineBottom,
    JSON.stringify(menuLandscape));

  await page.evaluate(() => document.getElementById('newcampaign').click());
  await sleep(200);
  const campaignLandscape = await page.evaluate(() => {
    const root = document.getElementById('smCamp').getBoundingClientRect();
    const footer = document.getElementById('smCampButtons').getBoundingClientRect();
    const list = document.getElementById('osadaCampList');
    return {
      root: { left: Math.round(root.left), top: Math.round(root.top), right: Math.round(root.right), bottom: Math.round(root.bottom) },
      footer: { left: Math.round(footer.left), top: Math.round(footer.top), right: Math.round(footer.right), bottom: Math.round(footer.bottom) },
      listScrollable: list.scrollHeight > list.clientHeight,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    };
  });
  ok('landscape campaign footer stays inside the panel',
    campaignLandscape.footer.top >= campaignLandscape.root.top &&
      campaignLandscape.footer.bottom <= campaignLandscape.root.bottom + 1 &&
      campaignLandscape.footer.left >= campaignLandscape.root.left &&
      campaignLandscape.footer.right <= campaignLandscape.root.right + 1,
    JSON.stringify(campaignLandscape));
  ok('landscape campaign list has its own scroll area', campaignLandscape.listScrollable);
  await page.evaluate(() => document.getElementById('smCBackBut').click());
  await sleep(120);

  await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await sleep(250);
  const portraitMain = await page.evaluate(() => {
    const buttons = [...document.querySelectorAll('#smButtons > *')];
    const root = document.getElementById('smMain');
    root.scrollTop = 0;
    document.getElementById('smButtons').scrollTop = 0;
    const tagline = document.getElementById('smLogoText').getBoundingClientRect();
    const firstVisible = buttons.find((el) => getComputedStyle(el).display !== 'none');
    const firstBeforeScroll = firstVisible.getBoundingClientRect();
    const links = document.getElementById('smMiscButs').getBoundingClientRect();
    const language = document.querySelector('.osada-language-switch').getBoundingClientRect();
    const overlaps = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
    const headerOverlap = overlaps(links, language);
    root.scrollTop = root.scrollHeight;
    const outside = buttons.map((el) => {
      const r = el.getBoundingClientRect();
      return { id: el.id, left: Math.round(r.left), right: Math.round(r.right) };
    }).filter((r) => r.left < -1 || r.right > window.innerWidth + 1);
    const last = buttons.at(-1).getBoundingClientRect();
    return {
      cls: document.body.className,
      outside,
      taglineBottom: Math.round(tagline.bottom), firstTop: Math.round(firstBeforeScroll.top), headerOverlap,
      lastTop: Math.round(last.top), lastBottom: Math.round(last.bottom), viewport: window.innerHeight,
    };
  });
  ok('portrait main menu is one column with no horizontal clipping',
    /osada-orientation-portrait/.test(portraitMain.cls) && portraitMain.outside.length === 0,
    JSON.stringify(portraitMain));
  ok('portrait main menu keeps its final command reachable',
    portraitMain.lastTop >= 0 && portraitMain.lastBottom <= portraitMain.viewport + 1,
    JSON.stringify(portraitMain));
  ok('portrait start-menu header and tagline do not collide with controls',
    portraitMain.firstTop >= portraitMain.taglineBottom && !portraitMain.headerOverlap,
    JSON.stringify(portraitMain));

  await page.evaluate(() => document.getElementById('newcampaign').click());
  await sleep(200);
  const campaignPortrait = await page.evaluate(() => {
    const rootEl = document.getElementById('smCamp');
    const body = document.getElementById('smCampBody');
    const footerEl = document.getElementById('smCampButtons');
    const root = rootEl.getBoundingClientRect();
    const footer = footerEl.getBoundingClientRect();
    const controls = ['osadaRulesButton-campaign', 'campaignRunExport', 'campaignRunImport', 'smCBackBut', 'smCPlayBut']
      .map((id) => {
        const el = document.getElementById(id);
        const r = el.getBoundingClientRect();
        return { id, left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width),
          height: Math.round(r.height), label: el.getAttribute('aria-label') || el.textContent.trim() };
      });
    return {
      root: { left: Math.round(root.left), top: Math.round(root.top), right: Math.round(root.right), bottom: Math.round(root.bottom) },
      footer: { left: Math.round(footer.left), top: Math.round(footer.top), right: Math.round(footer.right), bottom: Math.round(footer.bottom) },
      bodyOverflow: body.scrollWidth - body.clientWidth,
      rootOverflow: rootEl.scrollWidth - rootEl.clientWidth,
      controls,
      viewport: { width: window.innerWidth, height: window.innerHeight },
    };
  });
  ok('portrait campaign workspace does not overflow horizontally',
    campaignPortrait.bodyOverflow <= 1 && campaignPortrait.rootOverflow <= 1,
    JSON.stringify(campaignPortrait));
  ok('portrait campaign footer remains fully reachable',
    campaignPortrait.footer.left >= campaignPortrait.root.left &&
      campaignPortrait.footer.right <= campaignPortrait.root.right + 1 &&
      campaignPortrait.footer.bottom <= campaignPortrait.root.bottom + 1,
    JSON.stringify(campaignPortrait));
  ok('portrait campaign footer controls are compact, labelled, and touch sized',
    campaignPortrait.controls.every((c) => c.width >= 44 && c.height >= 44 && c.label.length > 0) &&
      [...campaignPortrait.controls].sort((a, b) => a.left - b.left)
        .every((c, i, all) => i === 0 || c.left >= all[i - 1].right - 1),
    JSON.stringify(campaignPortrait.controls));
  await page.evaluate(() => document.getElementById('smCBackBut').click());

  // The story stylesheet is lazy-loaded by ScenarioBriefingController. Load it explicitly and
  // exercise the same DOM contract here so portrait overflow cannot regress unnoticed.
  await page.evaluate(async () => {
    if (document.querySelector('link[href$="osada-briefing.css"]')) return;
    await new Promise((resolve, reject) => {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = 'css/osada-briefing.css';
      link.onload = resolve;
      link.onerror = reject;
      document.head.appendChild(link);
    });
  });
  const storyPortrait = await page.evaluate(async () => {
    const node = (tag, cls, parent, text = '') => {
      const el = document.createElement(tag);
      el.className = cls;
      el.textContent = text;
      parent.appendChild(el);
      return el;
    };
    const ordersRoot = node('div', 'osada-briefing', document.body);
    ordersRoot.style.visibility = 'hidden';
    const ordersShade = node('div', 'osada-briefing__shade', ordersRoot);
    const ordersShell = node('div', 'osada-briefing__shell', ordersShade);
    const ordersHeader = node('header', 'osada-briefing__header', ordersShell);
    node('h1', 'osada-briefing__title', ordersHeader, 'RP Guerrilla Hunter');
    const ordersStage = node('section', 'osada-briefing__orders', ordersShell);
    const ordersPanel = node('div', 'osada-briefing__orders-panel', ordersStage);
    node('div', 'osada-briefing__orders-eyebrow', ordersPanel, 'ЭСТОНИЯ ПРОТИВ СОВЕТСКИЙ СОЮЗ');
    const ordersContent = node('div', 'osada-briefing__orders-content', ordersPanel);
    for (let i = 0; i < 4; i++) {
      const section = node('section', 'osada-briefing__order-section', ordersContent);
      node('h2', 'osada-briefing__order-heading', section, 'БОЕВАЯ ЗАДАЧА');
      node('p', 'osada-briefing__order-text', section,
        'Hold the approaches, preserve the marked formation and keep the road open until the operation can begin.');
    }
    const ordersFooter = node('footer', 'osada-briefing__footer', ordersPanel);
    const begin = node('button', 'osada-briefing__button osada-briefing__button--primary', ordersFooter, 'НАЧАТЬ ОПЕРАЦИЮ');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const footerStyle = getComputedStyle(ordersFooter);
    const footerRect = ordersFooter.getBoundingClientRect();
    const beginRect = begin.getBoundingClientRect();
    const ordersFooterLayout = {
      position: footerStyle.position,
      backgroundImage: footerStyle.backgroundImage,
      backgroundColor: footerStyle.backgroundColor,
      footerWidth: Math.round(footerRect.width),
      beginWidth: Math.round(beginRect.width),
    };
    ordersRoot.remove();

    const root = node('div', 'osada-briefing osada-briefing--dialogue', document.body);
    root.id = 'mobile-story-fixture';
    root.style.visibility = 'hidden';
    const shade = node('div', 'osada-briefing__shade', root);
    const shell = node('div', 'osada-briefing__shell', shade);
    const header = node('header', 'osada-briefing__header', shell);
    const titleBlock = node('div', 'osada-briefing__title-block', header);
    node('h1', 'osada-briefing__title', titleBlock, '1919/04/16 Escaping from Zilah (Zalau)');
    node('div', 'osada-briefing__subtitle', titleBlock, 'ACT I — THE EASTERN FRONT BREAKS · ZILAH–NAGYKAROLY');
    node('div', 'osada-briefing__date', header, 'Wed Apr 16 1919');
    const stage = node('section', 'osada-dialogue-stage', shell);
    const panel = node('div', 'osada-dialogue', stage);
    const transcript = node('div', 'osada-dialogue__transcript', panel);
    for (let i = 0; i < 2; i++) {
      const turn = node('article', i ? 'osada-dialogue__turn osada-dialogue__turn--right' : 'osada-dialogue__turn', transcript);
      node('div', 'osada-dialogue__portrait', turn);
      const body = node('div', 'osada-dialogue__body', turn);
      node('div', 'osada-dialogue__speaker', body, i ? 'BÉLA KUN' : 'VILMOS BÖHM');
      node('p', 'osada-dialogue__text', body,
        'The Romanian army crossed at dawn. Keep the railway open long enough to save the numbered formations and every remaining supply train.');
    }
    const controls = node('div', 'osada-dialogue__controls osada-dialogue__controls--deciding', panel);
    const choices = node('div', 'osada-dialogue__choices', controls);
    choices.style.display = 'grid';
    for (let i = 0; i < 2; i++) {
      const choice = node('button', 'osada-dialogue__choice', choices);
      node('span', 'osada-dialogue__choice-number', choice, String(i + 1));
      node('span', 'osada-dialogue__choice-text', choice,
        'Fortify the station and approaches. Every additional hour lets another organised formation escape west.');
      node('span', 'osada-dialogue__choice-hint', choice,
        'Gain an organised detachment and protect the retreat, but surrender movement on the roads.');
    }
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const frame = root.getBoundingClientRect();
    const measured = [...root.querySelectorAll('.osada-dialogue__body, .osada-dialogue__choice')].map((el) => {
      const r = el.getBoundingClientRect();
      return { cls: el.className, left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width) };
    });
    const outside = measured.filter((r) => r.left < frame.left - 1 || r.right > frame.right + 1);
    const result = {
      frame: { left: Math.round(frame.left), right: Math.round(frame.right) },
      outside,
      controlsScrollable: controls.scrollHeight > controls.clientHeight,
      controlsOverflowY: getComputedStyle(controls).overflowY,
      ordersFooterLayout,
    };
    root.remove();
    return result;
  });
  ok('portrait story dialogue cards and choices stay inside the viewport',
    storyPortrait.outside.length === 0,
    JSON.stringify(storyPortrait));
  ok('long portrait story decisions have a vertical scroll owner',
    storyPortrait.controlsOverflowY === 'auto',
    JSON.stringify(storyPortrait));
  ok('portrait operational briefing footer stays in flow without a dark overlay',
    storyPortrait.ordersFooterLayout.position === 'static' &&
      storyPortrait.ordersFooterLayout.backgroundImage === 'none' &&
      storyPortrait.ordersFooterLayout.backgroundColor === 'rgba(0, 0, 0, 0)' &&
      storyPortrait.ordersFooterLayout.beginWidth >= storyPortrait.ordersFooterLayout.footerWidth - 1,
    JSON.stringify(storyPortrait.ordersFooterLayout));

  const legacyNarrative = await page.evaluate(async () => {
    const root = document.getElementById('ui-message');
    const title = root.querySelector('.uiMessageBoxTitle');
    const body = root.querySelector('.uiMessageBoxBody');
    const button = root.querySelector('.uiMessageBoxButton');
    const previous = { rootClass: root.className, display: root.style.display, title: title.innerHTML, body: body.innerHTML };
    root.classList.add('uiMessageBox--narrative');
    root.style.display = 'block';
    title.textContent = 'OPERATION HOOPER — CUITO CUANAVALE (1987)';
    body.innerHTML = Array.from({ length: 20 }, (_, i) => `<p>Long briefing paragraph ${i + 1}. Orders and historical context remain readable.</p>`).join('');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const rr = root.getBoundingClientRect();
    const br = button.getBoundingClientRect();
    const result = {
      bodyScrollable: body.scrollHeight > body.clientHeight && getComputedStyle(body).overflowY === 'auto',
      buttonInsideDialog: br.top >= rr.top && br.bottom <= rr.bottom + 1,
      buttonInsideViewport: br.top >= 0 && br.bottom <= window.innerHeight + 1,
    };
    root.className = previous.rootClass;
    root.style.display = previous.display;
    title.innerHTML = previous.title;
    body.innerHTML = previous.body;
    return result;
  });
  ok('long legacy briefing scrolls while its close control stays reachable',
    legacyNarrative.bodyScrollable && legacyNarrative.buttonInsideDialog && legacyNarrative.buttonInsideViewport,
    JSON.stringify(legacyNarrative));

  await page.setViewport({ width: 667, height: 375, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  await sleep(250);

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

  const phoneHud = await page.evaluate(() => {
    const metric = (selector) => {
      const el = document.querySelector(selector);
      const r = el.getBoundingClientRect();
      return { display: getComputedStyle(el).display, width: Math.round(r.width), height: Math.round(r.height) };
    };
    const card = document.getElementById('unit-info').getBoundingClientRect();
    const name = document.getElementById('uName').getBoundingClientRect();
    const expand = document.getElementById('uc-expand').getBoundingClientRect();
    const actionRects = [...document.querySelectorAll('#unit-context .osada-action')]
      .map((el) => el.getBoundingClientRect());
    return {
      title: metric('.osada-tb-op'),
      saved: metric('.osadaSaveStatus'),
      report: metric('#combatLogButton'),
      reserves: metric('.osada-tb-reserves'),
      reserveIcon: metric('.osada-tb-reserves__ico'),
      reserveBadgeInside: (() => {
        const button = document.querySelector('.osada-tb-reserves').getBoundingClientRect();
        const badgeEl = document.getElementById('osadaReservesBadge');
        if (getComputedStyle(badgeEl).display === 'none') return true;
        const badge = badgeEl.getBoundingClientRect();
        return badge.left >= button.left - 5 && badge.right <= button.right + 5;
      })(),
      nameVisible: name.width > 0 && name.height > 0,
      statsAboveActions: actionRects.length > 0 && expand.bottom <= Math.min(...actionRects.map((r) => r.top)) + 1,
      actionsInsideCard: actionRects.every((r) => r.left >= card.left - 1 && r.right <= card.right + 1),
    };
  });
  ok('phone HUD replaces clipped title/save text with the existing 44px Turn Report control',
    phoneHud.title.display === 'none' && phoneHud.saved.display === 'none' &&
      phoneHud.report.width >= 44 && phoneHud.report.height >= 44,
    JSON.stringify(phoneHud));
  ok('phone reserves artwork fills its touch plate',
    phoneHud.reserves.width >= 44 && phoneHud.reserves.height >= 44 &&
      phoneHud.reserveIcon.width >= 38 && phoneHud.reserveBadgeInside,
    JSON.stringify(phoneHud));

  const mobileContext = await page.evaluate(async () => {
    const bottom = document.getElementById('osada-bottomzone');
    const savedClass = bottom.className;
    bottom.classList.remove('bz--visible', 'bz--hover', 'bz--enemy-only');
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const dock = document.getElementById('osadaMobileContextDock');
    const visible = getComputedStyle(dock).display !== 'none';
    const result = {
      visible,
      turn: document.getElementById('osadaMobileTurn').textContent.trim(),
      weatherWidth: Math.round(document.getElementById('osadaMobileWeather').getBoundingClientRect().width),
      heroesWidth: Math.round(document.getElementById('osadaMobileHeroes').getBoundingClientRect().width),
    };
    bottom.className = savedClass;
    return result;
  });
  ok('passive lower rail shows turn/weather/heroes and yields to the unit card',
    mobileContext.visible && mobileContext.turn.length > 0 &&
      mobileContext.weatherWidth > 0 && mobileContext.heroesWidth >= 44,
    JSON.stringify(mobileContext));

  const reportEntry = await page.evaluate(async () => {
    document.getElementById('combatLogButton').click();
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const log = document.getElementById('combatLog');
    const result = {
      open: getComputedStyle(log).display !== 'none',
      hasBriefingAction: !!log.querySelector('.osada-tr-briefing-btn'),
    };
    document.getElementById('combatLogButton').click();
    return result;
  });
  ok('mobile report icon opens the full combat log surface with its briefing action',
    reportEntry.open && reportEntry.hasBriefingAction, JSON.stringify(reportEntry));
  ok('landscape unit identity remains visible and actions stay inside the card below All Stats',
    phoneHud.nameVisible && phoneHud.statsAboveActions && phoneHud.actionsInsideCard,
    JSON.stringify(phoneHud));

  const statsDismiss = await page.evaluate(async () => {
    const button = document.getElementById('uc-expand');
    const root = document.getElementById('unit-info');
    button.click();
    const opened = root.classList.contains('uc--expanded') && button.getAttribute('aria-expanded') === 'true';
    document.getElementById('game').dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'touch' }));
    await new Promise((r) => requestAnimationFrame(r));
    return { opened, closed: !root.classList.contains('uc--expanded'), aria: button.getAttribute('aria-expanded') };
  });
  ok('All Stats dismisses on a pointer press outside the sheet',
    statsDismiss.opened && statsDismiss.closed && statsDismiss.aria === 'false',
    JSON.stringify(statsDismiss));

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
    const close = document.getElementById('osadaDrawerClose').getBoundingClientRect();
    const air = document.getElementById('air').getBoundingClientRect();
    const logEl = document.getElementById('osadaLog');
    const log = logEl.getBoundingClientRect();
    return {
      sidebarLeft: Math.round(sidebar.left), before, after, expanded,
      minimapX: mr.left + mr.width / 2, minimapY: mr.top + mr.height / 2,
      minimapWidth: Math.round(mr.width), minimapHeight: Math.round(mr.height),
      close: { left: Math.round(close.left), top: Math.round(close.top), width: Math.round(close.width), height: Math.round(close.height) },
      airRight: Math.round(air.right),
      log: { top: Math.round(log.top), bottom: Math.round(log.bottom), height: Math.round(log.height), overflowY: getComputedStyle(logEl).overflowY },
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
  ok('drawer close control is a touch-sized X beside Air',
    drawer.close.width >= 44 && drawer.close.height >= 44 && drawer.close.left >= drawer.airRight,
    JSON.stringify(drawer.close));
  ok('landscape drawer keeps a visible, independently scrollable log',
    drawer.log.height >= 88 && drawer.log.bottom <= 375 && drawer.log.overflowY === 'auto',
    JSON.stringify(drawer.log));
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
