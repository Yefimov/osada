/**
 * End-to-end probe for the roadmap's P2 workstreams, driven through the real UI rather than
 * fixtures:
 *
 * - the main-menu **Hero Desk** entry is ALWAYS present (unlike the Hall of Fame button it
 *   replaced) and opens a desk with filters, search and an explanatory empty state;
 * - a seeded profile hero archive produces real cards, whose dossier opens with its service
 *   history and WITHOUT the Locate action (there is no map on the main menu);
 * - the Hall of Fame is a filter inside the desk, and a migrated legacy summary opens the
 *   explicitly limited dossier instead of an empty full one;
 * - the opt-in **Enhanced side markers** checkbox exists in Settings, defaults off, toggles the
 *   real `uiSettings` flag, and survives a settings save/restore round trip.
 *
 * See docs/design/hero-desk-and-profile-archive.md and docs/design/accessible-side-identification.md.
 */
import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8841;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=decodeURIComponent(rq.url.split('?')[0]);const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1440,height:900});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const results=[]; const ok=(n,c,extra='')=>results.push([c?'PASS':'FAIL',n,extra]);
// Report whatever was collected before an unexpected failure, so a broken step is diagnosable
// from the probe's own output instead of a bare stack trace.
const report=()=>{ console.log('\n=== P2 hero desk / side markers probe ===\n');
  for (const [s,n,e] of results) console.log(`${s}  ${n}${e?'\n        '+e:''}`);
  const f=results.filter(r=>r[0]==='FAIL').length;
  console.log(`\n${results.length-f}/${results.length} passed`); return f; };
process.on('uncaughtException', async e => { console.error('\nPROBE ERROR: '+e.message); report();
  await browser.close(); server.close(); process.exit(1); });

// The archive key is namespaced by the app's major version, and on a fresh profile no other
// `osada-*` key exists yet to copy it from, so read it from the single source of truth the way
// save-recovery-p0-probe.mjs does.
const CONSTANTS = path.resolve(__dirname,'..','..','src','jsMain','kotlin','org','osada','Constants.kt');
const major = (fs.readFileSync(CONSTANTS,'utf8').match(/const val VERSION = "(\d+)\.(\d+)/)||[]).slice(1,3).join('.');

// This probe asserts English UI strings, so pin the language before the app boots instead of
// inheriting the host machine's browser locale (`I18n.initialLanguage` reads this key on load).
await page.evaluateOnNewDocument(()=>{ try { localStorage.setItem('osada-language','en'); } catch (e) {} });

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(2500);
ok('probe pinned the UI language to English', await page.evaluate(()=>document.documentElement.lang==='en'),
   await page.evaluate(()=>document.documentElement.lang));
ok('probe resolved the storage version namespace', /^\d+\.\d+$/.test(major), major);

// ---------------------------------------------------------------- Hero Desk button + empty state

ok('Hero Desk entry is on the main menu with no archive at all',
   await page.evaluate(()=>!!document.getElementById('heroDesk')));
ok('the old Hall of Fame button is gone',
   await page.evaluate(()=>!document.getElementById('hallOfFame')));

await page.evaluate(()=>document.getElementById('heroDesk').click());
await sleep(400);
const empty = await page.evaluate(()=>{
  const box=document.getElementById('uiHeroDesk');
  return {
    open: !!box,
    filters: [...(box?.querySelectorAll('.osada-hero-desk-filters .osada-hero-tab')||[])].map(b=>b.textContent),
    search: !!document.getElementById('uiHeroDeskSearch'),
    emptyText: box?.querySelector('.osada-hero-empty')?.textContent || '',
  };
});
ok('the desk opens', empty.open);
ok('five filters including Hall of Fame', empty.filters.length===5 && empty.filters.includes('Hall of Fame'),
   empty.filters.join(' | '));
ok('search control present', empty.search);
ok('empty archive explains itself instead of hiding the surface', empty.emptyText.length>0, empty.emptyText);

// Escape closes it rather than opening the pause menu behind it.
await page.keyboard.press('Escape'); await sleep(250);
ok('Escape closes the desk', await page.evaluate(()=>!document.getElementById('uiHeroDesk')));

// ----------------------------------------------------- seeded archive + legacy Hall of Fame entry

const seeded = await page.evaluate((major)=>{
  const roster = {
    version: 1, drought: 0, reservedLegendary: '', legendarySpawned: 0,
    formations: [{
      id: 'F-0-1', owner: 0, country: 1, name: '3rd Guards Brigade', eqid: 900, uclass: 0,
      hero: 'H-1', recognition: 70, checks: 3, attachments: [], honors: ['Kiel'],
      history: [{ event: 'scenario_completed', scenario: 'Kiel', turn: 12 }],
    }],
    heroes: [{
      id: 'H-1', origin: 'PROCEDURAL', name: 'Anna Voroshina', background: 'armored_academy_graduate',
      signature: '', portraitSeed: 41, portraitLayers: [], portraitArt: '', portraitFemale: 'true',
      portraitPool: '', bio: { emergence: 'destroyed_stronger', birthYear: 1912 },
      rank: 'major', status: 'ACTIVE', potential: 'DISTINGUISHED', renown: 'LEGEND', xp: 310,
      formation: 'F-0-1', traits: [], attributes: {}, evidence: {}, promotions: 2,
      settling: { scenario: '', untilTurn: 0 }, nickname: '',
      medals: [{ medal: 'valor_medal', scenario: 'Kiel' }], injuries: [],
      events: [{ event: 'destroyed_stronger', scenario: 'Kiel', turn: 4 }],
    }],
  };
  const archive = {
    schemaVersion: 1,
    campaigns: [{
      campaignRunId: 'probe-camp.json', runEpoch: 'e1', campaignFile: 'probe-camp.json',
      campaignName: 'Probe Campaign', lastScenarioId: 'Kiel', lastScenarioIndex: 3,
      updatedAt: Date.now(), runStatus: 'COMPLETED', roster: JSON.stringify(roster),
      formationExperience: [{ id: 'F-0-1', xp: 240 }],
    }],
    legacy: [],
  };
  const key = 'osada-hero-archive-' + major;
  localStorage.setItem(key, JSON.stringify(archive));
  localStorage.setItem('osada_hall_of_fame', JSON.stringify([
    { name: 'Pavel Belov', rank: 'Colonel', renown: 'Legend', potential: 'Legendary',
      status: 'Killed in action', campaign: 'An Older Campaign' },
  ]));
  return { key, stored: !!localStorage.getItem(key) };
}, major);
ok('archive seeded under the versioned key', seeded.stored, JSON.stringify(seeded));

await page.evaluate(()=>document.getElementById('heroDesk').click());
await sleep(500);
const seededView = await page.evaluate(()=>{
  const cards=[...document.querySelectorAll('#uiHeroDeskList .osada-hero-desk-card')];
  return {
    count: cards.length,
    names: cards.map(c=>c.querySelector('.osada-hero-rosterrow-name')?.textContent),
    subs: cards.map(c=>[...c.querySelectorAll('.osada-hero-rosterrow-sub')].map(s=>s.textContent).join(' // ')),
    sources: cards.map(c=>c.querySelector('.osada-hero-desk-source')?.textContent||''),
    deleteActions: cards.filter(c=>c.querySelector('button')).length,
    roles: cards.map(c=>c.getAttribute('role')),
  };
});
ok('archived career and legacy summary both produce cards', seededView.count===2, JSON.stringify(seededView.names));
ok('archived commander is named with rank', (seededView.names[0]||'').includes('Anna Voroshina'), seededView.names.join(' | '));
ok('a completed run presents its survivor as retired',
   seededView.subs.some(s=>s.includes('Retired from this campaign')), seededView.subs.join(' || '));
ok('status and renown are rendered as text, not colour alone',
   seededView.subs.some(s=>s.includes('Active') && s.includes('Legend')), seededView.subs.join(' || '));
ok('provenance notes distinguish archive from legacy',
   seededView.sources.includes('Archived career') && seededView.sources.includes('Legacy record'),
   seededView.sources.join(' | '));
ok('delete-career action offered for the archived (non-resumable) career',
   seededView.deleteActions===1, `${seededView.deleteActions} card(s) with an action`);
ok('cards are keyboard-activatable buttons',
   seededView.roles.every(r=>r==='button'), seededView.roles.join(' | '));

// ------------------------------------------------------------------------------- filters + search

const filtered = await page.evaluate(()=>{
  const tabs=[...document.querySelectorAll('#uiHeroDesk .osada-hero-desk-filters .osada-hero-tab')];
  const count=()=>document.querySelectorAll('#uiHeroDeskList .osada-hero-desk-card').length;
  const byLabel={};
  for (const tab of tabs) { tab.click(); byLabel[tab.textContent]=count(); }
  tabs.find(t=>t.textContent==='All').click();
  const search=document.getElementById('uiHeroDeskSearch');
  search.value='voroshina'; search.dispatchEvent(new Event('input'));
  const searched=count();
  search.value=''; search.dispatchEvent(new Event('input'));
  return { byLabel, searched };
});
ok('Active excludes a completed run\'s survivors', filtered.byLabel['Active']===0, JSON.stringify(filtered.byLabel));
ok('Legendary finds the LEGEND-renown commander', filtered.byLabel['Legendary']===1, JSON.stringify(filtered.byLabel));
ok('Hall of Fame is a filter and admits both records', filtered.byLabel['Hall of Fame']===2, JSON.stringify(filtered.byLabel));
ok('search narrows to the matching commander', filtered.searched===1, `${filtered.searched}`);

// --------------------------------------------------------------------------------------- dossiers

const fullDossier = await page.evaluate(()=>{
  const card=[...document.querySelectorAll('#uiHeroDeskList .osada-hero-desk-card')]
    .find(c=>c.querySelector('.osada-hero-rosterrow-name')?.textContent?.includes('Anna Voroshina'));
  card.click();
  const box=document.getElementById('uiLeaderDossier');
  const tabs=[...(box?.querySelectorAll('.osada-hero-tab')||[])].map(t=>t.textContent);
  const locate=!!box?.querySelector('.osada-hero-locate');
  // Service Record tab carries the biography + service events.
  const service=[...(box?.querySelectorAll('.osada-hero-tab')||[])].find(t=>/Service/i.test(t.textContent));
  service?.click();
  const lines=[...(box?.querySelectorAll('.osada-hero-line')||[])].map(l=>l.textContent);
  return { open: !!box, tabs, locate, lines };
});
ok('an archived card opens the full leader dossier', fullDossier.open);
ok('all five dossier tabs are present', fullDossier.tabs.length===5, fullDossier.tabs.join(' | '));
ok('the Locate action is absent for an archived career', !fullDossier.locate);
ok('service history survived the archive round trip', fullDossier.lines.length>0, fullDossier.lines.join(' // '));

await page.keyboard.press('Escape'); await sleep(250);
ok('Escape closes the dossier and leaves the desk open',
   await page.evaluate(()=>!document.getElementById('uiLeaderDossier') && !!document.getElementById('uiHeroDesk')));

const legacyDossier = await page.evaluate(()=>{
  const card=[...document.querySelectorAll('#uiHeroDeskList .osada-hero-desk-card')]
    .find(c=>c.querySelector('.osada-hero-rosterrow-name')?.textContent?.includes('Pavel Belov'));
  card.click();
  const box=document.getElementById('uiHeroDeskLegacy');
  return {
    open: !!box,
    full: !!document.getElementById('uiLeaderDossier'),
    notice: box?.querySelector('.osada-hero-desk-warning')?.textContent||'',
  };
});
ok('a legacy summary opens the limited dossier, not the full one',
   legacyDossier.open && !legacyDossier.full, JSON.stringify(legacyDossier));
ok('the limited dossier says why it is limited',
   /Legacy record/.test(legacyDossier.notice), legacyDossier.notice);

// ----------------------------------------------------------------------- enhanced side markers

await page.keyboard.press('Escape'); await sleep(200);
await page.keyboard.press('Escape'); await sleep(200);
await page.evaluate(()=>window.game.ui.startMenuButton('settings'));
await sleep(600);
const markers = await page.evaluate(()=>{
  const box=document.getElementById('enhancedSideMarkers');
  const before=window.game.state ? null : null;
  const label=box?.closest('.settingContainer')?.querySelector('.settingText')?.textContent||'';
  const defaultOff=!box?.classList.contains('checked');
  box?.click();
  const afterClick=box?.classList.contains('checked');
  return { present: !!box, label, help: box?.title||'', defaultOff, afterClick };
});
ok('Enhanced side markers checkbox is in Settings', markers.present);
ok('it is labelled and explained', markers.label.length>0 && markers.help.length>20,
   `${markers.label} :: ${markers.help}`);
ok('it defaults to off', markers.defaultOff);
ok('clicking it turns it on', markers.afterClick);

const persisted = await page.evaluate(()=>{
  document.getElementById('smSetOkBut').click();
  const key=Object.keys(localStorage).find(k=>k.startsWith('osada-settings-'));
  return JSON.parse(localStorage.getItem(key)).enhancedSideMarkers;
});
ok('the setting is serialized with settings, not with a campaign save', persisted===true, `${persisted}`);

ok('no runtime JS errors', errs.length===0, errs.join(' | '));

const failed = report();
await browser.close(); server.close();
process.exit(failed?1:0);
