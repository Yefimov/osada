/** Real Chromium media + two-tab ownership, using the shipped catalog. No game RNG/debug exports. */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';
import { getChromePath } from 'chrome-launcher';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const dist = process.env.OSADA_DIST_DIR || path.join(root, 'build/dist/js/productionExecutable');
const report = process.env.OSADA_MUSIC_REPORT_DIR ? path.resolve(root,process.env.OSADA_MUSIC_REPORT_DIR) : path.join(root, 'build/reports/music');
const catalog = JSON.parse(fs.readFileSync(path.join(dist, 'resources/sounds/music/catalog.json'), 'utf8'));
const campaignCollections = {
  'camp6bn9.json':['eastern-front','instrumental','spanish-republic'],
  'camp6bn5.json':['eastern-front','instrumental','soviet-partisans'],
  'camp6bn8.json':['yugoslav-partisans','instrumental'],
  'reddestiny.json':['eastern-front','instrumental','ww2-remembrance'],
  'ncampdfn.json':['korean-war-dprk','instrumental'], 'aljf.json':['instrumental'],
  'rhu.json':['revolution-1917','instrumental'],
  'ccampdfc.json':['revolution-1917','civil-war-red-army'],
  'novemberrevolution.json':['german-communists','revolution-1917','instrumental'],
  'volarm.json':['revolution-1917','civil-war-red-army'],
  'acampdf2.json':['revolution-1917','civil-war-red-army'],
  'polsov.json':['revolution-1917','civil-war-red-army'],
  'gce.json':['spanish-republic','antifascist-resistance','instrumental'],
  'ga4.json':['eastern-front','instrumental'], 'spa.json':['instrumental'],
  'nvc.json':['vietnam-revolutionary','instrumental'], 'rcampdfr.json':['eastern-front','instrumental'],
  'rsoc.json':['chinese-revolution','instrumental'], '062d.json':['eastern-front','instrumental'],
  'camp6.json':['eastern-front','instrumental'],
  'camp6bn4.json':['greek-resistance','greek-democratic-army','instrumental'],
  'forward.json':['eastern-front','instrumental'],
  'simpob.json':['revolution-1917','civil-war-red-army']
};
const idsForCollections=ids=>[...new Set(catalog.collections.filter(c=>ids.includes(c.id)).flatMap(c=>c.recordingIds))].sort();
fs.mkdirSync(report, { recursive: true });
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json',
  '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.xml': 'text/xml' };
const server = http.createServer((req, res) => {
  const file = path.resolve(dist, '.' + (req.url.split('?')[0] === '/' ? '/index.html' : decodeURI(req.url.split('?')[0])));
  if (!file.startsWith(path.resolve(dist) + path.sep)) { res.writeHead(403).end(); return; }
  try {
    const size = fs.statSync(file).size;
    const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
    const start = range ? Number(range[1]) : 0;
    const end = range && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    const headers = { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream',
      'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1 };
    if (range) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
    res.writeHead(range ? 206 : 200, headers);
    fs.createReadStream(file, { start, end }).pipe(res);
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = process.env.OSADA_MUSIC_LIVE_URL || `http://127.0.0.1:${server.address().port}/`;
const browser = await puppeteer.launch({ executablePath: getChromePath(), headless: true,
  args: ['--no-sandbox', '--autoplay-policy=document-user-activation-required'] });
const errors = [];
const results = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (name, test) => { assert.ok(test, name); results.push(name); console.log('PASS', name); };

async function newPage(context = browser.defaultBrowserContext(), fallback = false, seed = null) {
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  page.on('pageerror', error => errors.push(error.message));
  await page.evaluateOnNewDocument(({fallback,seed}) => {
    if(seed) localStorage.setItem('osada-music-v1',JSON.stringify(seed));
    if (fallback) Object.defineProperty(navigator, 'locks', { value: undefined });
    const NativeAudio = window.Audio;
    window.__audios = [];
    window.Audio = function (...args) { const audio = new NativeAudio(...args); window.__audios.push(audio); return audio; };
  }, {fallback,seed});
  await page.setViewport({ width: 1280, height: 720 });
  await page.goto(url, { waitUntil: 'networkidle0' });
  await page.waitForSelector('#osadaMusicMenu button[data-music-toggle]:not(:disabled)', {timeout: 10000}).catch(async e => {
    console.log(await page.evaluate(() => ({menu:document.querySelector('#osadaMusicMenu')?.outerHTML,
      settings:localStorage.getItem('osada-music-v1'), body:document.body.innerHTML.slice(-300)})));
    await page.screenshot({path: path.join(report, 'boot-failure.png')}); throw e;
  });
  return page;
}
const snapshot = page => page.evaluate(() => {
  const audio = window.__audios.filter(a => (a.src.includes('/sounds/music/') && !a.src.endsWith('amb_field.mp3'))).at(-1);
  return { src: audio?.src, paused: audio?.paused, time: audio?.currentTime || 0, duration: audio?.duration,
    volume: audio?.volume, muted: audio?.muted, loop: audio?.loop,
    audible: window.__audios.filter(a => (a.src.includes('/sounds/music/') && !a.src.endsWith('amb_field.mp3')) && !a.paused).length,
    status: document.querySelector('#osadaMusicStatus')?.textContent,
    library: document.querySelector('#osadaMusicLibrary') ? getComputedStyle(document.querySelector('#osadaMusicLibrary')).display : 'none' };
});
const open = page => page.click('#osadaMusicMenu [data-music-open]');
const playing = page => page.waitForFunction(() => window.__audios.some(a => (a.src.includes('/sounds/music/') && !a.src.endsWith('amb_field.mp3')) && !a.paused && a.currentTime > 0));

try {
  const page = await newPage();
  check('MP-01 fresh page makes no audio request/stream', !(await snapshot(page)).src);
  await open(page);
  await page.screenshot({ path: path.join(report, 'catalog-desktop.png') });
  await page.click('#osadaMusicToggle');
  await playing(page);
  check('fresh instrumental mix opens with owner-supplied Popov Night',
    (await snapshot(page)).src.endsWith('osada-popov-night-v1.ogg'));
  check('explicit Play starts real non-looping audio at 20%', (await snapshot(page)).volume === 0.2 && !(await snapshot(page)).loop);
  await page.click('#osadaMusicToggle');
  const pausedTime = (await snapshot(page)).time;
  await delay(300);
  check('Pause preserves position', (await snapshot(page)).paused && Math.abs((await snapshot(page)).time - pausedTime) < 0.1);

  await page.click('[data-music-focus="inspect:og-scores"]');
  check('MP-03 inspection does not enable collection', !await page.$eval('#musicInclude-og-scores', e => e.checked));
  await page.click('#musicInclude-og-scores');
  await page.click('#musicInclude-instrumental');
  await page.click('[data-music-focus="inspect:instrumental"]');
  await page.click('#musicTrack-osada-popov-chapaev-overture');
  // Anchor the two-recording history test independently of the expanded opening album.
  await page.click('[data-music-focus="inspect:og-scores"]');
  await page.click('[data-music-focus="play:og-african-drums"]');
  await playing(page);
  await page.click('#osadaMusicToggle');
  await page.click('#osadaMusicNext');
  check('MP-02 Next while paused selects at zero and stays paused', (await snapshot(page)).paused !== false);
  await page.click('#osadaMusicToggle');
  await playing(page);
  await page.waitForFunction(() => window.__audios.some(a => a.src.endsWith('osada-popov-chapaev-overture-v1.ogg') && a.duration > 0));
  check('next plays second shipped recording', (await snapshot(page)).src.endsWith('osada-popov-chapaev-overture-v1.ogg'));
  await page.click('[data-music-focus="inspect:instrumental"]');
  await page.click('#musicTrack-osada-popov-chapaev-overture');
  check('removing the currently playing recording lets it finish once', (await snapshot(page)).audible===1 &&
    !await page.$eval('#musicTrack-osada-popov-chapaev-overture',e=>e.checked));
  await page.click('#musicTrack-osada-popov-chapaev-overture');
  await page.$eval('#osadaMusicSeek', e => { e.value = '5'; e.dispatchEvent(new Event('input', { bubbles: true })); });
  await delay(200);
  check('MP-09 seek updates actual media', (await snapshot(page)).time >= 5);
  await page.click('#osadaMusicMute');
  check('MP-13 independent music mute', (await snapshot(page)).muted);
  await page.click('#osadaMusicMute');
  await page.$eval('#osadaMusicVolume', e=>{e.value='0';e.dispatchEvent(new Event('input',{bubbles:true}));});
  await page.click('#osadaMusicMute');
  check('Unmute at zero restores the remembered nonzero music level', (await snapshot(page)).volume===.2 && !(await snapshot(page)).muted);
  const beforeClose = (await snapshot(page)).time;
  await page.click('#osadaMusicClose');
  await open(page);
  check('MP-10 reopening library preserves media', (await snapshot(page)).time >= beforeClose && (await snapshot(page)).audible === 1);
  await page.focus('#osadaMusicSearch');
  await page.keyboard.press('Escape');
  if ((await snapshot(page)).library === 'flex') {
    check('Escape closes settings disclosure first', !await page.$('.osada-music-options[open]'));
    await page.keyboard.press('Escape');
  }
  check('MP-18 Escape in search closes just music', (await snapshot(page)).library === 'none');
  await open(page);
  await page.screenshot({ path: path.join(report, 'desktop.png') });

  const second = await newPage();
  check('MP-17 new tab is silent', (await snapshot(second)).audible === 0 && (await snapshot(page)).audible === 1);
  await second.click('#osadaMusicMenu [data-music-toggle]');
  await playing(second);
  await delay(250);
  check('MP-17 explicit Play transfers ownership', (await snapshot(page)).audible === 0 && (await snapshot(second)).audible === 1);
  await second.click('#osadaMusicMenu [data-music-toggle]');
  await Promise.all([page.$eval('#osadaMusicToggle', e => e.click()), second.$eval('#osadaMusicMenu [data-music-toggle]', e => e.click())]);
  await delay(300);
  check('MP-17 simultaneous actions have at most one audible stream', (await snapshot(page)).audible + (await snapshot(second)).audible <= 1);
  await second.close();
  await page.click('#osadaMusicToggle');
  await delay(150);
  if ((await snapshot(page)).audible === 0) await page.click('#osadaMusicToggle');
  await playing(page);
  check('closed owner recovery works', (await snapshot(page)).audible === 1);
  await page.click('#osadaMusicToggle');

  // One recording selection is reflected in every shared collection membership.
  await page.click('[data-music-focus="inspect:german-communists"]');
  await page.click('#musicInclude-german-communists');
  check('collection selection makes overlapping Spanish collection indeterminate',
    await page.$eval('#musicInclude-spanish-republic', e => e.indeterminate && e.getAttribute('aria-checked') === 'mixed'));
  await page.click('#musicTrack-osada-spaniens-himmel');
  check('unchecking a shared track updates its whole collection tri-state',
    await page.$eval('#musicInclude-german-communists', e => e.indeterminate));
  await page.click('[data-music-focus="inspect:spanish-republic"]');
  check('shared recording stays unchecked in other collection', !await page.$eval('#musicTrack-osada-spaniens-himmel', e => e.checked));
  await page.$eval('#osadaMusicSearch', e => { e.value = 'Jarama'; e.dispatchEvent(new Event('input', {bubbles:true})); });
  await page.click('#musicInclude-spanish-republic');
  check('collection select-all ignores search scope', await page.evaluate(() => {
    const s = JSON.parse(localStorage.getItem('osada-music-v1')).selected;
    return s.includes('osada-spaniens-himmel') && s.includes('osada-cuatro-generales');
  }));
  await page.$eval('#osadaMusicSearch', e => { e.value = ''; e.dispatchEvent(new Event('input', {bubbles:true})); });
  await page.click('#musicTrack-osada-spaniens-himmel');
  const selectionBeforePreview = await page.evaluate(() => JSON.parse(localStorage.getItem('osada-music-v1')).selected);
  await page.click('[data-music-focus="play:osada-spaniens-himmel"]');
  await playing(page);
  check('unchecked direct play is a preview without selection changes',
    JSON.stringify(selectionBeforePreview) === JSON.stringify(await page.evaluate(() => JSON.parse(localStorage.getItem('osada-music-v1')).selected)));
  await page.click('#osadaMusicToggle');
  check('preview can be paused', (await snapshot(page)).audible === 0);
  await page.click('#osadaMusicNext');
  check('Next after preview preserves manual pause', (await snapshot(page)).audible === 0);
  await page.$eval('#osadaMusicVolume', e => { e.value = '0.35'; e.dispatchEvent(new Event('input', { bubbles: true })); });
  await delay(500);
  await page.reload({ waitUntil: 'networkidle0' });
  check('MP-12 returning visit remains silent with saved volume', (await snapshot(page)).audible === 0 && await page.evaluate(() => JSON.parse(localStorage.getItem('osada-music-v1')).volume === 0.35));

  // Desktop menu handle, separately saved picker position and viewport reclamping.
  async function dragMenu(dx,dy) {
    const h=await page.$eval('#osadaMusicMenu [data-music-drag]',e=>{const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};});
    await page.mouse.move(h.x,h.y);await page.mouse.down();await page.mouse.move(h.x+dx,h.y+dy,{steps:8});await page.mouse.up();
  }
  await dragMenu(-200,-100);
  check('menu dragging persists normalized position and does not open library', await page.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1')); return p.positions.menu.x>=0 && p.positions.menu.x<=1 &&
      p.positions.menu.y>=0 && p.positions.menu.y<=1 && document.querySelector('#osadaMusicLibrary')?.style.display!=='flex';
  }));
  await page.click('#newcampaign');
  check('picker reserves player header space above selectable cards',await page.evaluate(()=>{
    const m=document.querySelector('#osadaMusicMenu').getBoundingClientRect();
    const b=document.querySelector('#smCamp .osadaScreenBody').getBoundingClientRect();return m.bottom<=b.top && m.width>0;
  }));
  await dragMenu(-100,10);
  check('picker and menu have independently saved positions',await page.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));return p.positions.picker && p.positions.menu;
  }));
  await page.screenshot({path:path.join(report,'picker.png')});
  await page.click('#smCBackBut');
  await page.setViewport({width:1024,height:768});
  await delay(200);
  check('resizing reclamps the menu player inside the viewport',await page.$eval('#osadaMusicMenu',e=>{
    const r=e.getBoundingClientRect();return r.x>=0&&r.y>=0&&r.right<=innerWidth+1&&r.bottom<=innerHeight+1;
  }));
  await open(page);
  check('transport uses decorative SVGs with localized accessible action names',await page.evaluate(()=>{
    return ['osadaMusicToggle','osadaMusicNext','osadaMusicPrevious','osadaMusicMute'].every(id=>{
      const e=document.getElementById(id);return e.getAttribute('aria-label')&&e.title&&e.querySelector('svg[aria-hidden="true"]');
    }) && !document.querySelector('#osadaMusicQueueTab,#osadaMusicTracksTab,#osadaMusicSource,[data-music-focus^="more:"]');
  }));
  await page.click('.osada-music-options summary');
  await page.waitForFunction(()=>document.querySelector('.osada-music-options summary').getAttribute('aria-expanded')==='true');
  check('settings gear announces expanded state and explains opt-in resume',await page.evaluate(()=>{
    const e=document.querySelector('.osada-music-options summary');return e.getAttribute('aria-expanded')==='true'&&
      document.querySelector('.osada-music-help').textContent.length>80&&!document.querySelector('#osadaMusicResume').checked;
  }));
  await page.keyboard.press('Escape');
  check('Escape yields only the open settings disclosure first',(await snapshot(page)).library==='flex');
  await page.click('.osada-music-options summary');
  await page.focus('[data-music-label="reset_position"]');
  await page.keyboard.press('Enter');
  check('keyboard reset clears saved menu positions',await page.evaluate(()=>Object.keys(JSON.parse(localStorage.getItem('osada-music-v1')).positions).length===0));
  await page.click('[data-music-focus="inspect:instrumental"]');
  await page.click('[data-music-focus="play:osada-shostakovich-romance-1919-adriano"]');
  await playing(page);
  await page.click('#osadaMusicToggle');
  await page.click('#osadaMusicClose');
  const opener = '#osadaMusicMenu [data-music-open]';
  await page.hover(opener);
  await page.waitForFunction(()=>document.querySelector('#osadaMusicMenu [data-music-open]').classList.contains('osada-music-title-overflow'));
  check('overflowing title animates only on fine-pointer hover',await page.$eval(`${opener} span`,e=>getComputedStyle(e).animationName==='osada-music-title-scroll'));
  await page.mouse.move(5,5);
  check('leaving the title resets its animation',await page.$eval(`${opener} span`,e=>getComputedStyle(e).animationName==='none'));
  await page.emulateMediaFeatures([{name:'prefers-reduced-motion',value:'reduce'}]);
  await page.hover(opener);
  check('reduced-motion keeps overflowing title stationary',await page.$eval(`${opener} span`,e=>getComputedStyle(e).animationName==='none'));
  await page.emulateMediaFeatures([{name:'prefers-reduced-motion',value:'no-preference'}]);
  await page.mouse.move(5,5);
  await page.focus(opener);
  check('keyboard focus exposes full title and performer tooltip',await page.$eval('.osada-music-title-tooltip',e=>e.textContent.includes('Adriano')&&e.getBoundingClientRect().right<=innerWidth));
  await page.keyboard.press('Tab');
  await page.setViewport({width:640,height:360,deviceScaleFactor:2,isMobile:false,hasTouch:false});
  await open(page);
  check('200 percent equivalent CSS viewport keeps library controls reachable',await page.evaluate(()=>{
    const section=document.querySelector('#osadaMusicLibrary section'),r=section.getBoundingClientRect();
    return r.right<=innerWidth+1&&r.bottom<=innerHeight+1&&section.scrollWidth<=section.clientWidth+1&&
      document.querySelector('#osadaMusicClose').getBoundingClientRect().width>=32;
  }));
  await page.screenshot({path:path.join(report,'zoom-200-equivalent.png')});
  await page.click('#osadaMusicClose');
  for (const [width, height, touch] of [[1920,1080,false],[1024,768,false],[1024,768,true],[667,375,true],[844,390,true],[390,844,true]]) {
    await page.setViewport({ width, height, hasTouch: touch, isMobile: touch });
    await page.waitForSelector('#osadaMusicMenu [data-music-open]');
    await delay(300);
    await page.evaluate(() => {
      document.querySelector('#osadaGestureTutorial .osada-btn--primary')?.click();
      document.querySelector('#osadaOrientationGate .osada-btn--primary')?.click();
    });
    if ((await snapshot(page)).library !== 'flex') await open(page);
    await page.screenshot({ path: path.join(report, `${width}x${height}${touch ? '-touch' : ''}.png`) });
    const fit = await page.evaluate(() => {
      const root = document.querySelector('#osadaMusicLibrary');
      const box = root.querySelector('section');
      const rect = box.getBoundingClientRect();
      const close = document.querySelector('#osadaMusicClose').getBoundingClientRect();
      return rect.right <= visualViewport.width + 1 && rect.bottom <= visualViewport.height + 1 &&
        box.scrollWidth <= box.clientWidth + 1 && close.height >= (document.body.classList.contains('osada-input-coarse') ? 44 : 32);
    });
    check(`MP-21/22 library fits ${width}×${height}${touch ? ' touch' : ''}`, fit);
    if (height < 480) {
      await page.evaluate(() => {
        // Portrait navigation may show collections first; inspection switches to the track pane.
        document.querySelector('[data-music-focus="inspect:og-scores"]').click();
        document.querySelector('[data-music-focus="play:og-african-drums"]').scrollIntoView({block: 'nearest'});
      });
      const reachable = await page.evaluate(() => {
        const row = document.querySelector('[data-music-focus="play:og-african-drums"]');
        const rect = row.getBoundingClientRect();
        const pane = row.closest('.osada-music-tracks').getBoundingClientRect();
        return rect.top >= pane.top - 1 && rect.bottom <= pane.bottom + 1;
      });
      check(`MP-22 short layout keeps actual track controls reachable ${width}×${height}`, reachable);
      await page.screenshot({ path: path.join(report, `${width}x${height}-tracks.png`) });
    }
    await page.click('#osadaMusicClose');
  }

  // Real scenario lifecycle: African Drums is now a normal selection preset.
  const scenarioContext = await browser.createBrowserContext();
  const battle = await newPage(scenarioContext);
  await open(battle);
  await battle.click('#osadaMusicToggle');
  await playing(battle);
  const mixBeforeScenario = await snapshot(battle);
  async function loadBattle(file = 'battle_cuito.xml') {
    await battle.evaluate(file => {
      document.querySelector('#startmenu').style.display = 'none';
      window.game.campaign = null; window.game.newScenario(file, 'Music lifecycle probe');
    }, file);
    await battle.waitForFunction(file => window.game.scenario?.isLoaded && window.game.scenario.file === file, {}, file);
    await battle.waitForFunction(() => document.querySelector('#ui-message')?.style.display !== 'none', {timeout:30000});
    await battle.waitForFunction(() => document.activeElement.id === 'uiokbut', {timeout:30000});
  }
  await loadBattle();
  check('MP-10/19 required ceremony yields library while current music finishes',
    (await snapshot(battle)).library === 'none' && (await snapshot(battle)).audible === 1 &&
    (await snapshot(battle)).src === mixBeforeScenario.src && (await snapshot(battle)).time >= mixBeforeScenario.time);
  check('MP-19 mandatory decision receives focus', true);
  await battle.click('#uiokbut');
  await battle.click('#osadaMusicSidebar [data-music-open]');
  await battle.click('#osadaMusicToggle');
  check('first African scenario silently initializes ordinary one-track mix', await battle.evaluate(() => {
    const p = JSON.parse(localStorage.getItem('osada-music-v1'));
    return p.selected.length === 1 && p.selected[0] === 'og-african-drums' && !document.querySelector('#osadaMusicSource');
  }));
  await battle.click('#osadaMusicNext');
  check('scenario preset preserves manual pause', (await snapshot(battle)).audible === 0);
  await battle.click('#osadaMusicToggle');
  await playing(battle);
  check('African preset plays the existing allowed recording', (await snapshot(battle)).src.endsWith('africa2.mp3'));
  await loadBattle();
  check('scenario restart leaves current recording uninterrupted', (await snapshot(battle)).audible === 1);
  await battle.click('#uiokbut');
  await battle.click('#osadaMusicSidebar [data-music-open]');
  await battle.evaluate(() => {
    const audio = window.__audios.filter(a => a.src.endsWith('africa2.mp3')).at(-1);
    audio.currentTime = audio.duration - 0.1;
  });
  await battle.waitForFunction(() => /ended|завершено/i.test(document.querySelector('#osadaMusicStatus').textContent));
  check('MP-06 ordinary single-recording mix ends without looping', (await snapshot(battle)).audible === 0);
  // Campaign presets include whole collections; custom selection survives scenario changes.
  await battle.evaluate(() => { window.game.newCampaign(campaignlist.findIndex(c=>c.file==='volarm.json'),100); });
  await battle.waitForFunction(()=>window.game.campaign?.file==='volarm.json'&&window.game.scenario?.isLoaded, {timeout:30000});
  await delay(600);
  check('Civil War preset includes every song in both collections', await battle.evaluate(async () => {
    const s=JSON.parse(localStorage.getItem('osada-music-v1')).selected;
    const catalog=await fetch('resources/sounds/music/catalog.json').then(r=>r.json());
    const ids=[...new Set(catalog.collections.filter(c=>['revolution-1917','civil-war-red-army'].includes(c.id)).flatMap(c=>c.recordingIds))];
    return s.length===ids.length&&ids.every(id=>s.includes(id));
  }));
  await battle.evaluate(()=>{
    document.querySelector('#uiokbut')?.click();
    const briefing=document.querySelector('#osada-scenario-briefing');
    if(briefing&&getComputedStyle(briefing).display!=='none') {
      const skip=briefing.querySelector('.osada-dialogue__skip');
      if(skip?.getBoundingClientRect().width) skip.click();
      briefing.querySelector('.osada-briefing__orders .osada-briefing__button--primary')?.click();
    }
  });
  await battle.click('#osadaMusicSidebar [data-music-open]');
  await battle.waitForFunction(()=>document.querySelector('#osadaMusicLibrary').style.display==='flex');
  await battle.click('[data-music-focus="inspect:eastern-front"]');
  await battle.click('#musicTrack-osada-dark-night');
  const customCivilMix=await battle.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected);
  await battle.click('#osadaMusicMatch');
  check('Match campaign replaces custom selection without starting music',await battle.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));return !p.selected.includes('osada-dark-night')&&
      window.__audios.filter(a=>!a.loop).every(a=>a.paused);
  }));
  await battle.click('[data-music-label="undo"]');
  check('Undo restores exact custom campaign selection',JSON.stringify(customCivilMix)===JSON.stringify(await battle.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected)));
  await battle.click('#osadaMusicClose');
  await battle.evaluate(()=>window.game.newScenario(window.game.scenario.file,'Saved mix lifecycle'));
  await battle.waitForFunction(()=>window.game.scenario?.isLoaded,{timeout:30000});
  await delay(600);
  check('campaign scenario reload preserves manually selected later song',await battle.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.includes('osada-dark-night')));
  await battle.evaluate(()=>{window.game.campaign=null;window.game.newScenario('acampufa.xml','Unknown musical context');});
  await battle.waitForFunction(()=>window.game.scenario?.isLoaded&&window.game.scenario.file==='acampufa.xml',{timeout:30000});
  await delay(500);
  check('unknown musical context preserves the user mix',JSON.stringify(customCivilMix)===JSON.stringify(await battle.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected)));
  await battle.evaluate(()=>window.game.newCampaign(campaignlist.findIndex(c=>c.file==='camp6bn5.json'),100));
  await battle.waitForFunction(()=>window.game.campaign?.file==='camp6bn5.json'&&window.game.scenario?.isLoaded,{timeout:30000});
  await delay(500);
  check('wartime preset includes whole era and partisan collections regardless of song dates',await battle.evaluate(()=>{
    const s=JSON.parse(localStorage.getItem('osada-music-v1')).selected;
    return s.includes('osada-katyusha')&&s.includes('osada-sacred-war')&&s.includes('osada-dark-night')&&s.includes('osada-valleys-and-hills')&&s.includes('osada-popov-night')&&!s.includes('osada-roads');
  }));
  await loadBattle('acampufa.xml');
  await battle.click('#uiokbut');
  const parkSave = await battle.evaluate(() => {
    const payload=JSON.parse(window.game.state.exportGameState());
    payload.scenario.atmosferic=3; payload.scenario.ground=2;
    return JSON.stringify(payload);
  });
  await scenarioContext.close();

  // Environment: restore before a trusted gesture, then retry only the current loop.
  const environmentContext = await browser.createBrowserContext();
  const environment = await newPage(environmentContext);
  await environment.evaluate(() => {
    window.uiSettings.muteUnitSounds=true; window.uiSettings.ambientVolume=.27;
    window.__environmentNativePlay=HTMLMediaElement.prototype.play;
    window.__denyEnvironment=true; window.__environmentAttempts=0;
    HTMLMediaElement.prototype.play=function() {
      if (this.loop) {
        window.__environmentAttempts++;
        if (window.__denyEnvironment) return Promise.reject(new DOMException('Test policy','NotAllowedError'));
      }
      return window.__environmentNativePlay.call(this);
    };
  });
  await environment.evaluate(saved => window.game.state.restoreFromString(saved), parkSave);
  await environment.waitForFunction(() => window.__audios.some(a => a.src.endsWith('winter.mp3')));
  await delay(700);
  const envSnapshot = () => environment.evaluate(() => window.__audios.filter(a => a.loop).map(a=>({src:a.src,paused:a.paused,volume:a.volume,time:a.currentTime})));
  check('snow restore requests environment despite unit mute; rejected autoplay stays silent',
    (await envSnapshot()).some(a=>a.src.endsWith('winter.mp3')) && (await envSnapshot()).every(a=>a.paused));
  const attempts=await environment.evaluate(()=>window.__environmentAttempts);
  await delay(250);
  check('environment autoplay denial does not poll', attempts===await environment.evaluate(()=>window.__environmentAttempts));
  await environment.evaluate(()=>{window.__denyEnvironment=false;});
  await environment.mouse.click(10,10);
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop && !a.paused && a.currentTime>0));
  check('trusted gesture retries the current winter loop', (await envSnapshot()).filter(a=>!a.paused).length===1);
  async function restoreSky(sky, file='acampufa.xml') {
    const data=JSON.parse(parkSave); data.scenario.atmosferic=sky; data.scenario.file=file; if(file==='battle_cuito.xml') data.scenario.options.music='AFRICA2.mp3';
    await environment.evaluate(saved=>window.game.state.restoreFromString(saved), JSON.stringify(data));
    await delay(600);
  }
  await restoreSky(2);
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop && !a.paused && a.src.endsWith('rain.mp3')));
  check('rain supersedes Park without layering', (await envSnapshot()).filter(a=>!a.paused).length===1);
  await restoreSky(1);
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop && !a.paused && a.src.endsWith('amb_field.mp3')));
  check('precipitation ending resumes current authored Park environment', (await envSnapshot()).filter(a=>!a.paused).length===1);
  const catalogEnvironment=await environment.evaluate(async()=>await (await fetch('resources/sounds/music/catalog.json')).json());
  check('Park is absent from music and all collection memberships', !catalogEnvironment.recordings.some(r=>r.id==='og-park-ambience') && catalogEnvironment.collections.every(c=>!c.recordingIds.includes('og-park-ambience')));
  await environment.evaluate(()=>{document.querySelector('#ambientvolume').value='0';document.querySelector('#ambientvolume').dispatchEvent(new Event('input',{bubbles:true}));});
  check('environment zero immediately stops Park', (await envSnapshot()).every(a=>a.paused));
  await environment.evaluate(()=>{document.querySelector('#ambientvolume').value='.3';document.querySelector('#ambientvolume').dispatchEvent(new Event('input',{bubbles:true}));});
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop&&!a.paused));
  check('raising environment volume immediately restores the current loop', (await envSnapshot()).some(a=>!a.paused&&a.volume===.3));
  await restoreSky(1,'battle_cuito.xml');
  check('overcast without authored Park stops environment even with frozen ground', (await envSnapshot()).every(a=>a.paused));
  await restoreSky(3);
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop&&!a.paused));
  const otherEnvironment = await newPage(environmentContext);
  await otherEnvironment.bringToFront();
  await delay(200);
  check('hidden game tab suspends its environment', (await envSnapshot()).every(a=>a.paused));
  await environment.bringToFront();
  await environment.waitForFunction(()=>window.__audios.some(a=>a.loop&&!a.paused));
  check('visible game tab restores its current environmental state', (await envSnapshot()).filter(a=>!a.paused).length===1);
  await otherEnvironment.close();
  await environment.evaluate(()=>window.game.newScenario('battle_cuito.xml','Environment exit probe'));
  await delay(100);
  check('scenario replacement immediately stops the old environmental loop', (await envSnapshot()).every(a=>a.paused));
  await environmentContext.close();

  // Exercise the storage/BroadcastChannel fallback in an independent profile.
  const fallbackContext = await browser.createBrowserContext();
  const a = await newPage(fallbackContext, true);
  const b = await newPage(fallbackContext, true);
  await Promise.all([a.$eval('#osadaMusicMenu [data-music-toggle]', e => e.click()), b.$eval('#osadaMusicMenu [data-music-toggle]', e => e.click())]);
  await delay(700);
  check('MP-17 fallback simultaneous starts choose one owner', (await snapshot(a)).audible + (await snapshot(b)).audible === 1);
  await fallbackContext.close();

  const failureContext = await browser.createBrowserContext();
  const failure = await newPage(failureContext);
  await open(failure);
  await failure.click('[data-music-focus="inspect:og-scores"]');
  await failure.click('#musicInclude-og-scores');
  await failure.click('#musicInclude-instrumental');
  await failure.setRequestInterception(true);
  let failAudio = true;
  let audioRequests = 0;
  failure.on('request', request => {
    if (request.url().includes('/sounds/music/') && request.url().includes('.mp3')) {
      audioRequests++;
      if (failAudio) { request.abort(); return; }
    }
    request.continue();
  });
  const failingCollectionControl=await failure.$('#musicInclude-og-scores');
  await failure.click('#osadaMusicToggle');
  await failure.waitForFunction(() => /unavailable|недоступны/i.test(document.querySelector('#osadaMusicStatus').textContent));
  check('MP-15 failed audio stops after a bounded pass', audioRequests <= 4 && (await snapshot(failure)).audible === 0);
  check('unavailable-only album disables its persistent inclusion control',await failingCollectionControl.evaluate(e=>e.isConnected&&e.disabled));
  failAudio = false;
  await failure.click('[data-music-label="retry"]');
  await playing(failure);
  check('MP-15 Retry clears temporary failures', (await snapshot(failure)).audible === 1);
  check('Retry re-enables the original album control',await failingCollectionControl.evaluate(e=>e.isConnected&&!e.disabled&&e.checked));
  await failureContext.close();

  const blockedContext = await browser.createBrowserContext();
  const blocked = await newPage(blockedContext);
  await blocked.evaluate(() => { HTMLMediaElement.prototype.play = () => Promise.reject(new DOMException('Test policy', 'NotAllowedError')); });
  await open(blocked);
  await blocked.click('#osadaMusicToggle');
  await blocked.waitForFunction(() => /press|нажмите/i.test(document.querySelector('#osadaMusicStatus').textContent));
  check('MP-01 autoplay denial is actionable, never false Playing', (await snapshot(blocked)).audible === 0);
  await blockedContext.close();

  // Exercise the newly shipped Opus -> MP3 transport fallback through the player.
  const codecContext = await browser.createBrowserContext();
  const codecPage = await newPage(codecContext);
  await codecPage.setRequestInterception(true);
  let rejectedOpus = 0;
  codecPage.on('request', request => {
    if (request.url().includes('/sounds/music/') && request.url().endsWith('.ogg')) {
      rejectedOpus++; request.abort(); return;
    }
    request.continue();
  });
  await codecPage.click('#osadaMusicMenu [data-music-toggle]');
  await playing(codecPage);
  check('failed Opus delivery falls back to the same recording in MP3',
    rejectedOpus === 1 && (await snapshot(codecPage)).src.endsWith('osada-popov-night-v1.mp3'));
  await codecContext.close();

  const migrationContext = await browser.createBrowserContext();
  const migration = await migrationContext.newPage();
  await migration.evaluateOnNewDocument(() => localStorage.setItem('osada-settings-3', JSON.stringify({ musicVolume: 0.45 })));
  await migration.goto(url, { waitUntil: 'networkidle0' });
  check('MP-12 legacy profile volume migrates once', await migration.evaluate(() => JSON.parse(localStorage.getItem('osada-music-v1')).volume === 0.45));
  await migration.evaluate(() => localStorage.setItem('osada-music-v1', '{corrupted'));
  await migration.reload({ waitUntil: 'networkidle0' });
  check('MP-16 corrupt music storage recovers independently', await migration.evaluate(() => JSON.parse(localStorage.getItem('osada-music-v1')).schemaVersion === 3));
  await migrationContext.close();
  const legacyContext = await browser.createBrowserContext();
  const legacy = await newPage(legacyContext, false, {schemaVersion:1,enabled:['instrumental','og-scores'],
    excluded:['osada-popov-night'],volume:.41,shuffle:false,source:'scenario',autoResume:true,userPlaying:true,
    checkpoint:{current:'og-park-ambience',position:12,history:['og-park-ambience']}});
  check('legacy collection/exclusion migration is deduplicated, preserves settings and safely drops Park', await legacy.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));
    return p.schemaVersion===3 && p.selected.length===6 && !p.selected.includes('osada-popov-night') &&
      !p.selected.includes('og-park-ambience') && p.volume===.41 && p.shuffle===false && p.userPlaying===false && !p.checkpoint;
  }) && (await snapshot(legacy)).audible===0);
  await legacyContext.close();
  const selectedContext = await browser.createBrowserContext();
  const selectedPage = await newPage(selectedContext,false,{schemaVersion:2, selected:['osada-popov-night','missing-id'], volume:.2});
  check('new recordings never join an existing selected set implicitly',await selectedPage.evaluate(()=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));return p.selected.length===1 && p.selected[0]==='osada-popov-night';
  }));
  await selectedContext.close();
  // The actual campaign picker must match the highlighted entry before any battle is started.
  const pickerContext=await browser.createBrowserContext();
  const picker=await newPage(pickerContext);
  await picker.click('#newcampaign');
  const registeredCampaigns=await picker.evaluate(()=>campaignlist.map((c,index)=>({file:c.file,index})));
  check('every registered campaign has an explicit music collection mapping',
    registeredCampaigns.length===23&&registeredCampaigns.every(c=>campaignCollections[c.file]));
  let customForward;
  for(const campaign of registeredCampaigns) {
    await picker.select('#smCampSel select',String(campaign.index));
    await open(picker);
    await picker.click('#osadaMusicMatch');
    const expected=idsForCollections(campaignCollections[campaign.file]);
    const actual=await picker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort());
    const checked=await picker.evaluate(ids=>ids.every(id=>{
      const box=document.getElementById('musicInclude-'+id);return box.checked&&!box.indeterminate;
    }),campaignCollections[campaign.file]);
    check(`campaign picker matches whole collections for ${campaign.file}`,checked&&JSON.stringify(actual)===JSON.stringify(expected));
    if(campaign.file==='ccampdfc.json') check('Bolshevik Cavalry includes Budyonny March and all revolutionary songs',actual.includes('osada-budyonny-march')&&actual.includes('osada-internationale'));
    if(campaign.file==='forward.json') {
      await picker.click('#musicInclude-instrumental');
      await picker.click('[data-music-focus="inspect:eastern-front"]');
      await picker.click('#musicTrack-osada-dark-night');
      customForward=await picker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort());
    }
    await picker.click('#osadaMusicClose');
  }
  check('browsing and matching all campaign playlists stays silent',!(await snapshot(picker)).src);
  await picker.select('#smCampSel select',String(registeredCampaigns.find(c=>c.file==='forward.json').index));
  await picker.click('#smCPlayBut');
  await picker.waitForFunction(()=>window.game.campaign?.file==='forward.json'&&window.game.scenario?.isLoaded,{timeout:30000});
  check('starting Forward preserves the mix customized in its picker',JSON.stringify(customForward)===JSON.stringify(await picker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
  await picker.waitForFunction(()=>['ui-message','osada-scenario-briefing'].some(id=>document.getElementById(id)?.getBoundingClientRect().width>0),{timeout:30000});
  await picker.evaluate(()=>{
    if(document.querySelector('#ui-message')?.getBoundingClientRect().width) document.querySelector('#uiokbut')?.click();
    const briefing=document.querySelector('#osada-scenario-briefing');
    if(briefing&&getComputedStyle(briefing).display!=='none') {
      const skip=briefing.querySelector('.osada-dialogue__skip');
      if(skip?.getBoundingClientRect().width) skip.click();
      briefing.querySelector('.osada-briefing__orders .osada-briefing__button--primary')?.click();
    }
  });
  await picker.click('#options');
  await picker.click('#newcampaign');
  await picker.select('#smCampSel select',String(registeredCampaigns.find(c=>c.file==='camp6bn4.json').index));
  await open(picker);
  await picker.click('#osadaMusicMatch');
  await picker.click('#osadaMusicClose');
  check('Greek picker matching does not replace the loaded Forward game or its saved custom mix',await picker.evaluate(expected=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));
    return game.campaign.file==='forward.json'&&JSON.stringify(p.campaignMixes.find(m=>m.key==='campaign:forward.json').selected.slice().sort())===JSON.stringify(expected);
  },customForward));
  await picker.click('#smCBackBut');
  await picker.click('#continuegame');
  // Reopen music from the battle's pause menu; deployment UI can cover the sidebar player.
  await picker.click('#options');
  await open(picker);
  await picker.waitForFunction(()=>document.querySelector('#osadaMusicLibrary').style.display==='flex');
  await picker.click('#osadaMusicMatch');
  check('Match after Continue uses Forward, never the Greek menu preview',
    JSON.stringify(idsForCollections(campaignCollections['forward.json']))===
      JSON.stringify(await picker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
  check('matching in the battle preserves the Greek campaign mix',await picker.evaluate(expected=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));
    return JSON.stringify(p.campaignMixes.find(m=>m.key==='campaign:camp6bn4.json').selected.slice().sort())===JSON.stringify(expected);
  },idsForCollections(campaignCollections['camp6bn4.json'])));
  await pickerContext.close();

  // Let the real 15-second owner timer run while a silent tab edits music settings.
  const checkpointContext=await browser.createBrowserContext();
  const owner=await newPage(checkpointContext);
  await open(owner);
  await owner.click('#osadaMusicToggle');
  await playing(owner);
  const editor=await newPage(checkpointContext);
  await editor.click('#newcampaign');
  await editor.select('#smCampSel select',String(registeredCampaigns.find(c=>c.file==='camp6bn4.json').index));
  await open(editor);
  await editor.click('#osadaMusicMatch');
  await editor.click('#osadaMusicShuffle');
  await editor.click('[data-music-focus="inspect:greek-resistance"]');
  await editor.$eval('#osadaMusicVolume',e=>{e.value='0.47';e.dispatchEvent(new Event('input',{bubbles:true}));});
  await editor.click('#osadaMusicMute');
  const editedSettings=await editor.evaluate(()=>localStorage.getItem('osada-music-v1'));
  const beforeCheckpoint=await owner.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1-checkpoint')));
  await delay(16000);
  const afterCheckpoint=await owner.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1-checkpoint')));
  check('real owner timer advances its checkpoint without rewriting second-tab settings',
    afterCheckpoint.current===beforeCheckpoint.current&&afterCheckpoint.position>beforeCheckpoint.position+10&&
      editedSettings===await editor.evaluate(()=>localStorage.getItem('osada-music-v1')));
  await owner.$eval('#osadaMusicVolume',e=>{e.value='0.31';e.dispatchEvent(new Event('input',{bubbles:true}));});
  check('stale owner volume edit preserves the other tab selection, campaign mixes and options',await owner.evaluate(previous=>{
    const before=JSON.parse(previous),after=JSON.parse(localStorage.getItem('osada-music-v1'));
    return after.volume===.31&&after.lastNonzero===.31&&
      ['selected','campaignMixes','shuffle','muted','inspected','positions','autoResume'].every(k=>JSON.stringify(before[k])===JSON.stringify(after[k]));
  },editedSettings));
  await owner.bringToFront();
  await owner.reload({waitUntil:'networkidle0'});
  check('owner pagehide and reload retain the second-tab selection without starting playback',
    await owner.evaluate(expected=>JSON.stringify(JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())===JSON.stringify(expected),
      idsForCollections(campaignCollections['camp6bn4.json']))&&(await snapshot(owner)).audible===0);
  await owner.click('#osadaMusicMenu [data-music-toggle]');
  await playing(owner);
  const restored=await snapshot(owner);
  check('explicit Play restores the owner recording and position from its separate checkpoint',
    restored.src.endsWith('osada-popov-night-v1.ogg')&&restored.time>=afterCheckpoint.position);
  await checkpointContext.close();

  // Exact old generated subsets upgrade once; differing saved custom mixes remain player-owned.
  const oldCivil=['osada-workers-marseillaise','osada-smelo-tovarishchi'];
  const instrumental=idsForCollections(['instrumental']);
  for(const item of [
    {file:'ccampdfc.json',saved:oldCivil,label:'old partial Bolshevik mix upgrades to both complete albums'},
    {file:'camp6bn4.json',saved:instrumental,label:'old Greek fallback upgrades to both complete Greek albums'},
    {file:'forward.json',label:'fresh Forward automatically selects whole wartime and instrumental albums'},
    {file:'forward.json',saved:['osada-dark-night'],custom:true,label:'schema 2 custom campaign mix survives the upgrade'},
    ...['ncampdfn.json','rsoc.json','nvc.json'].flatMap(file=>[
      {file,label:`fresh ${file} chooses its entire regional album and instrumental collection`},
      {file,saved:instrumental,custom:true,label:`${file} preserves its older saved instrumental-only mix`},
      {file,saved:['osada-dark-night'],custom:true,label:`${file} preserves a custom saved selection`}
    ])
  ]) {
    const context=await browser.createBrowserContext();
    const seed=item.saved?{schemaVersion:2,selected:item.saved,volume:.2,
      campaignMixes:[{key:'campaign:'+item.file,selected:item.saved}]}:null;
    const target=await newPage(context,false,seed);
    await target.click('#newcampaign');
    await target.select('#smCampSel select',String(registeredCampaigns.find(c=>c.file===item.file).index));
    await target.click('#smCPlayBut');
    await target.waitForFunction(file=>window.game.campaign?.file===file&&window.game.scenario?.isLoaded,{timeout:30000},item.file);
    const selected=await target.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort());
    const expected=item.custom?item.saved:idsForCollections(campaignCollections[item.file]);
    check(item.label,JSON.stringify(selected)===JSON.stringify(expected));
    check(`${item.file} automatic selection does not start playback`,!(await snapshot(target)).src);
    if (['ncampdfn.json','rsoc.json','nvc.json'].includes(item.file) && item.custom) {
      await target.waitForFunction(()=>['ui-message','osada-scenario-briefing'].some(id=>
        document.getElementById(id)?.getBoundingClientRect().width>0),{timeout:30000});
      await target.evaluate(()=>{
        if(document.querySelector('#ui-message')?.getBoundingClientRect().width) document.querySelector('#uiokbut')?.click();
        const briefing=document.querySelector('#osada-scenario-briefing');
        briefing?.querySelector('.osada-dialogue__skip')?.click();
        briefing?.querySelector('.osada-briefing__orders .osada-briefing__button--primary')?.click();
      });
      await target.click('#options');
      await open(target);
      await target.click('#osadaMusicMatch');
      check(`${item.file} explicit Match replaces the saved choice with complete regional collections`,
        JSON.stringify(idsForCollections(campaignCollections[item.file]))===JSON.stringify(await target.evaluate(()=>
          JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
      check(`${item.file} Match preserves silence`,!(await snapshot(target)).src);
      await target.click('#osadaMusicUndo button');
      check(`${item.file} Undo restores the earlier custom selection`,
        JSON.stringify(item.saved.slice().sort())===JSON.stringify(await target.evaluate(()=>
          JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
    }
    await context.close();
  }

  const cubaContext=await browser.createBrowserContext();
  const cuba=await newPage(cubaContext);
  await cuba.evaluate(()=>{
    document.querySelector('#startmenu').style.display='none';
    game.campaign=null; game.newScenario('cubali01.xml','Cuban music preset verification');
  });
  await cuba.waitForFunction(()=>game.scenario?.isLoaded&&game.scenario.file==='cubali01.xml',{timeout:30000});
  check('Sierra Maestra 1958 automatically chooses the entire Cuban and instrumental albums',
    JSON.stringify(idsForCollections(['cuban-revolution','instrumental']))===JSON.stringify(await cuba.evaluate(()=>
      JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
  check('Cuban scenario music initialization stays silent',!(await snapshot(cuba)).src);
  await cubaContext.close();
  for(const saved of [instrumental,[],['osada-dark-night']]) {
    const context=await browser.createBrowserContext();
    const target=await newPage(context,false,{schemaVersion:2,selected:saved.length?saved:instrumental,
      campaignMixes:[{key:'scenario:cubali01.xml',selected:saved}]});
    await target.evaluate(()=>{
      document.querySelector('#startmenu').style.display='none';
      game.campaign=null; game.newScenario('cubali01.xml','Saved Cuban mix verification');
    });
    await target.waitForFunction(()=>game.scenario?.isLoaded&&game.scenario.file==='cubali01.xml',{timeout:30000});
    check(`Cuban context preserves a saved ${saved.length}-track selection without autoplay`,
      JSON.stringify(saved.slice().sort())===JSON.stringify(await target.evaluate(()=>
        JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort()))&&!(await snapshot(target)).src);
    await context.close();
  }

  // Search spans every album and every title language while inspection remains local.
  const libraryContext=await browser.createBrowserContext();
  const library=await newPage(libraryContext);
  await open(library);
  const searchLibrary=async query=>library.$eval('#osadaMusicSearch',(e,q)=>{e.value=q;e.dispatchEvent(new Event('input',{bubbles:true}));},query);
  await searchLibrary('Bella Ciao');
  check('global search finds the requested Bella Ciao outside the inspected instrumental album',
    await library.$('#musicTrack-osada-bella-ciao')&&await library.$eval('[data-music-focus="inspect:instrumental"]',e=>e.getAttribute('aria-pressed')==='true'));
  check('active search scope explicitly describes the whole library',await library.$eval('#osadaMusicSearchScope',e=>/all tracks|все треки/i.test(e.textContent)));
  await library.screenshot({path:path.join(report,'global-search-bella.png')});
  await searchLibrary('Белла чао');
  check('global search accepts a Russian title regardless of interface locale',!!await library.$('#musicTrack-osada-bella-ciao'));
  await searchLibrary('Giải phóng miền Nam');
  check('global search accepts original Vietnamese titles in an unselected album',!!await library.$('#musicTrack-osada-vietnamese-liberate-south'));
  await searchLibrary('Ay Carmela');
  check('shared recordings occur only once in global search',await library.$$eval('#osadaMusicList input',e=>e.length===1&&e[0].id==='musicTrack-osada-ay-carmela'));
  await searchLibrary('Bella');
  await library.click('#musicTrack-osada-bella-ciao');
  check('selecting a global search result saves it without starting audio',
    await library.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.includes('osada-bella-ciao'))&&!(await snapshot(library)).src);
  await searchLibrary('');
  check('clearing search restores the inspected album and leaves other album selection intact',
    !await library.$('#musicTrack-osada-bella-ciao')&&await library.$('#musicTrack-osada-popov-night'));
  const checkbox=await library.$('#musicInclude-instrumental');
  const bounds=await checkbox.boundingBox();
  await library.mouse.click(bounds.x+bounds.width/2,bounds.y+bounds.height/2,{count:2,delay:70});
  const allIds=idsForCollections(catalog.collections.map(c=>c.id));
  check('real double click on instrumental includes every complete playlist with no duplicates',
    JSON.stringify(allIds)===JSON.stringify(await library.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort()))&&
    await library.$$eval('#osadaMusicCollections input',boxes=>boxes.every(e=>e.checked&&!e.indeterminate)));
  check('double click retains the same checkbox node and stays silent',await checkbox.evaluate(e=>e.isConnected)&&!(await snapshot(library)).src);
  await library.screenshot({path:path.join(report,'all-playlists.png')});
  await library.click('#musicInclude-instrumental');
  check('a later single click still deselects just the instrumental playlist and persists it',
    await library.evaluate(ids=>{const s=JSON.parse(localStorage.getItem('osada-music-v1')).selected;return ids.every(id=>!s.includes(id))&&s.includes('osada-bella-ciao');},instrumental));
  await library.reload({waitUntil:'networkidle0'});
  check('album checkbox changes survive reload without autoplay',await library.evaluate(ids=>{
    const s=JSON.parse(localStorage.getItem('osada-music-v1')).selected;return ids.every(id=>!s.includes(id))&&s.includes('osada-bella-ciao');
  },instrumental)&&!(await snapshot(library)).src);
  await libraryContext.close();

  const scenarioPickerContext=await browser.createBrowserContext();
  const scenarioPicker=await newPage(scenarioPickerContext);
  await scenarioPicker.click('#newscenario');
  const specialThemes={
    'cubali01.xml':['cuban-revolution','instrumental'],
    'op_hooper.xml':['cuban-revolution','instrumental'],
    '1954_dienbienphu.xml':['vietnam-revolutionary','instrumental'],
    'madrid_36.xml':['spanish-republic','antifascist-resistance','instrumental'],
    'mallorca.xml':['spanish-republic','antifascist-resistance','instrumental'],
    'teruel.xml':['spanish-republic','antifascist-resistance','instrumental'],
    'ebro.xml':['spanish-republic','antifascist-resistance','instrumental'],
    'aran.xml':['spanish-republic','antifascist-resistance','instrumental'],
    'khalkin1.xml':['eastern-front','instrumental'],
    'khalkin2.xml':['eastern-front','instrumental'],
    'partisanen.xml':['yugoslav-partisans','instrumental'],
    'sparta.xml':['german-communists','revolution-1917','instrumental'],
    '1920_perekop.xml':['revolution-1917','civil-war-red-army'],
    'battle_cuito.xml':['og-scores'],
    'vkampala_sfb.xml':['og-scores'],
    'forward13.xml':['eastern-front','instrumental'],
    'nvc07.xml':['vietnam-revolutionary','instrumental'],
    'gce2.xml':['spanish-republic','antifascist-resistance','instrumental'],
  };
  for(const [file,collections] of Object.entries(specialThemes)) {
    const index=await scenarioPicker.evaluate(file=>scenariolist.findIndex(r=>r[0]===file),file);
    assert.ok(index>=0,`registered historical battle ${file}`);
    await scenarioPicker.select('#smScenSel select',String(index));
    await open(scenarioPicker);
    await scenarioPicker.click('#osadaMusicMatch');
    check(`scenario picker Match chooses complete historical playlists for ${file}`,
      JSON.stringify(idsForCollections(collections))===JSON.stringify(await scenarioPicker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
    await scenarioPicker.click('#osadaMusicClose');
  }
  const madridIndex=await scenarioPicker.evaluate(()=>scenariolist.findIndex(r=>r[0]==='madrid_36.xml'));
  await scenarioPicker.select('#smScenSel select',String(madridIndex));
  await open(scenarioPicker);
  const instrumentBounds=await (await scenarioPicker.$('#musicInclude-instrumental')).boundingBox();
  await scenarioPicker.mouse.click(instrumentBounds.x+instrumentBounds.width/2,instrumentBounds.y+instrumentBounds.height/2,{count:2,delay:70});
  check('double click saves all playlists under the highlighted individual battle context',await scenarioPicker.evaluate(expected=>{
    const p=JSON.parse(localStorage.getItem('osada-music-v1'));
    return JSON.stringify(p.campaignMixes.find(m=>m.key==='scenario:madrid_36.xml').selected.slice().sort())===JSON.stringify(expected);
  },allIds));
  await scenarioPicker.click('#osadaMusicClose');
  await scenarioPicker.click('#smSPlayBut');
  await scenarioPicker.waitForFunction(()=>game.scenario?.isLoaded&&game.scenario.file==='madrid_36.xml',{timeout:30000});
  check('starting the highlighted standalone preserves all manually selected playlists without autoplay',
    JSON.stringify(allIds)===JSON.stringify(await scenarioPicker.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort()))&&!(await snapshot(scenarioPicker)).src);
  await scenarioPickerContext.close();

  // Launch real individual battles: no campaign object is needed to choose music.
  const historicalPresets=[
    ['madrid_36.xml',['spanish-republic','antifascist-resistance','instrumental']],
    ['1954_dienbienphu.xml',['vietnam-revolutionary','instrumental']],
    ['op_hooper.xml',['cuban-revolution','instrumental']],
    ['forward13.xml',['eastern-front','instrumental']],
  ];
  for(const [file,collections] of historicalPresets) {
    const context=await browser.createBrowserContext();
    const target=await newPage(context);
    await target.evaluate(file=>{
      document.querySelector('#startmenu').style.display='none';
      game.campaign=null;game.newScenario(file,'Historical music verification');
    },file);
    await target.waitForFunction(file=>game.scenario?.isLoaded&&game.scenario.file===file,{timeout:30000},file);
    check(`${file} outside a campaign initializes every track of its historical playlists`,
      JSON.stringify(idsForCollections(collections))===JSON.stringify(await target.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
    check(`${file} historical music selection remains silent`,!(await snapshot(target)).src);
    await context.close();
  }
  const standaloneCustomContext=await browser.createBrowserContext();
  const standaloneCustom=await newPage(standaloneCustomContext,false,{schemaVersion:2,selected:instrumental,
    campaignMixes:[{key:'scenario:madrid_36.xml',selected:instrumental}]});
  await standaloneCustom.evaluate(()=>{
    document.querySelector('#startmenu').style.display='none';game.campaign=null;game.newScenario('madrid_36.xml','Existing individual mix');
  });
  await standaloneCustom.waitForFunction(()=>game.scenario?.isLoaded&&game.scenario.file==='madrid_36.xml',{timeout:30000});
  check('new historical defaults preserve a player existing instrumental-only standalone mix',
    JSON.stringify(instrumental)===JSON.stringify(await standaloneCustom.evaluate(()=>JSON.parse(localStorage.getItem('osada-music-v1')).selected.slice().sort())));
  await standaloneCustomContext.close();

  // Every shipped variant must decode and seek over the same real HTTP delivery path.
  const regionalCounts={'korean-war-dprk':8,'cuban-revolution':9,'chinese-revolution':9,'vietnam-revolutionary':9};
  for (const [id,count] of Object.entries(regionalCounts)) {
    const collection=catalog.collections.find(c=>c.id===id);
    check(`${id} contains all ${count} ready bilingual recordings with originals and both codecs`,
      collection?.recordingIds.length===count&&collection.recordingIds.every(id=>{
        const r=catalog.recordings.find(r=>r.id===id);
        return r.originalTitle&&r.titleRu&&r.title&&r.variants.length===2&&
          r.variants.some(v=>v.codec==='opus'&&v.bitrateKbps>=64&&v.bitrateKbps<=80)&&
          r.variants.some(v=>v.codec==='mp3');
      }));
  }
  const russianInternationale=catalog.recordings.find(r=>r.id==='osada-internationale');
  const chineseInternationale=catalog.recordings.find(r=>r.id==='osada-internationale-chinese');
  check('Chinese Internationale shares only the work identity with the Russian recording',
    russianInternationale.workId===chineseInternationale.workId&&
      russianInternationale.variants.every(v=>!chineseInternationale.variants.some(c=>c.url===v.url)));
  const revisedChinese=catalog.recordings.find(r=>r.id==='osada-chinese-communist-new-china');
  check('New China lyric revision is dated separately from the original work',
    revisedChinese.workDate==='1943'&&revisedChinese.lyricDate==='1950'&&!revisedChinese.recordingDate);
  const recordingIds = new Set(catalog.recordings.map(r => r.id));
  assert.equal(recordingIds.size, catalog.recordings.length, 'unique recording identities');
  for (const collection of catalog.collections) {
    assert.ok(collection.recordingIds.every(id => recordingIds.has(id)), collection.id);
    assert.equal(new Set(collection.recordingIds).size, collection.recordingIds.length);
    assert.ok(fs.existsSync(path.join(dist, collection.artwork)), 'local collection artwork');
  }
  const decoding = await browser.newPage();
  const mediaResponses=[];
  decoding.on('response', response=>{
    if(/\/sounds\/music\/.+\.(ogg|mp3)(?:\?|$)/.test(response.url())) mediaResponses.push({url:response.url(),status:response.status()});
  });
  await decoding.goto(url, { waitUntil: 'networkidle0' });
  let variantsChecked = 0;
  for (const recording of catalog.recordings) {
    for (const variant of recording.variants) {
      const file = path.join(dist, variant.url);
      assert.ok(fs.existsSync(file), variant.url);
      if (variant.byteSize) assert.equal(fs.statSync(file).size, variant.byteSize);
      const result = await decoding.evaluate(async (variant, expected) => {
        const audio = new Audio();
        audio.muted = true;
        audio.preload = 'auto';
        try {
          await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('decode timeout')), 15000);
            audio.addEventListener('loadeddata', () => { clearTimeout(timer); resolve(); }, {once: true});
            audio.addEventListener('error', () => { clearTimeout(timer); reject(new Error('media ' + audio.error?.code)); }, {once: true});
            audio.src = variant.url;
          });
          if (expected && Math.abs(audio.duration - expected) > 0.3) throw new Error('duration mismatch');
          const duration = audio.duration;
          await new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('seek timeout')), 10000);
            audio.addEventListener('seeked', () => { clearTimeout(timer); resolve(); }, {once: true});
            audio.currentTime = duration / 2;
          });
          const position = audio.currentTime;
          await audio.play();
          await new Promise(resolve => setTimeout(resolve, 80));
          if (audio.paused || audio.currentTime <= position) throw new Error('decoded frames did not advance');
          return {duration};
        } catch (error) { return {error: error.message}; }
        finally { audio.pause(); audio.removeAttribute('src'); audio.load(); }
      }, variant, recording.duration);
      assert.ok(!result.error, `${recording.id} ${variant.mime}: ${result.error}`);
      variantsChecked++;
      if (variantsChecked % 20 === 0) console.log('Decoded and sought', variantsChecked, 'catalog variants');
    }
  }
  check(`all ${variantsChecked} shipped audio variants decode, seek and play`, variantsChecked > 0);
  const missingResponses=catalog.recordings.flatMap(r=>r.variants).filter(v=>
    !mediaResponses.some(r=>r.url===new URL(v.url,url).href&&[200,206,304].includes(r.status)));
  if(missingResponses.length) console.log('Unmatched media responses',JSON.stringify({missing:missingResponses.map(v=>v.url),responses:mediaResponses.slice(0,8)}));
  check('HTTP delivers every catalog variant successfully',missingResponses.length===0);
  await decoding.close();
  check('no runtime exceptions', errors.length === 0);
  fs.writeFileSync(path.join(report, 'results.json'), JSON.stringify({ url, results, errors }, null, 2));
} catch (error) {
  console.error(error);
  fs.writeFileSync(path.join(report, 'results.json'), JSON.stringify({ url, results, errors, failure: error.message }, null, 2));
  process.exitCode = 1;
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
