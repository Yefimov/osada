import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8805;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,200)));
const consoleErrs=[]; page.on('console',m=>{ if(m.type()==='error') consoleErrs.push(m.text().slice(0,200)); });
const results=[]; const ok=(n,c,extra)=>results.push([c?'PASS':'FAIL',n,extra]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);

// bn5s02.xml = eqp-lxf scenario with player 1 = Germany (canon country id 7), shared across 6
// source efiles (lxf/adlerkorps/atomic/basekorp/comww2/olgww2) -- exactly where the
// availability filter (or its absence) should show a visible difference in the buy list.
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('bn5s02.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(500);

const basics = await page.evaluate(() => {
  const g = window.game;
  const scenario = g.scenario;
  const players = scenario.map.getPlayers();
  return {
    eqp: scenario.eqp,
    playerCountries: players.map(p => ({ id: p.id, country: p.country, name: window.Equipment.getCountryName_56hbzh_k$(p.country) })),
  };
});
ok('scenario loaded is bn5s02 (eqp-lxf)', basics.eqp === 'eqp-lxf', basics.eqp);
ok('players have real (non-negative) country codes', basics.playerCountries.every(p => p.country >= 0), JSON.stringify(basics.playerCountries));
ok('player country names resolve (not "Unknown")', basics.playerCountries.every(p => p.name && p.name !== 'Unknown'), JSON.stringify(basics.playerCountries));
console.log('  player countries:', JSON.stringify(basics.playerCountries));

// Open the Buy window (default tab), read the rendered unit-card count -- WITH the
// availability filter enabled (default), then again with it disabled via the exposed
// window.Equipment singleton, to prove the filter is actually restricting the list (not a
// no-op fail-open). Reopening the same tab forces EquipmentWindowController to re-query.
const filterCompare = await page.evaluate(() => {
  // EquipmentWindowController reads map.currentPlayer -- force it to player 1 (Germany, id 7,
  // shared across 6 source efiles) so the buy list actually exercises the merge, regardless of
  // whose turn the fresh scenario started on.
  const map = window.game.scenario.map;
  map.currentPlayer = map.getPlayers().find(p => p.id === 1);
  window.game.ui.mainMenuButton('buy');
  const withFilter = document.querySelectorAll('#eqUnitList .eqUnitBox').length;
  window.game.ui.mainMenuButton('buy'); // close
  window.Equipment.set_availabilityFilterEnabled_6gz0jo_k$(false);
  window.game.ui.mainMenuButton('buy'); // reopen -> re-render
  const withoutFilter = document.querySelectorAll('#eqUnitList .eqUnitBox').length;
  window.Equipment.set_availabilityFilterEnabled_6gz0jo_k$(true); // restore default
  return { withFilter, withoutFilter };
});
console.log('  buy list size: filtered =', filterCompare.withFilter, ' unfiltered =', filterCompare.withoutFilter);

ok('buy window opens with a non-empty unit list', filterCompare.withFilter > 0, filterCompare.withFilter);
ok('disabling the availability filter grows the list', filterCompare.withoutFilter > filterCompare.withFilter,
   `filtered=${filterCompare.withFilter} unfiltered=${filterCompare.withoutFilter}`);

// Flags: fetch the unified flag assets directly and confirm they 200, and that a unit-info
// panel's flag background-image actually points at eqp-united (not a stale per-efile path).
const flagCheck = await page.evaluate(async () => {
  const g = window.game;
  const map = g.scenario.map;
  const anyUnit = map.getUnits()[0];
  if (anyUnit) g.ui.uiUnitSelect(anyUnit);
  await new Promise(r => setTimeout(r, 200));
  const uFlagStyle = getComputedStyle(document.getElementById('uFlag') || document.createElement('div')).backgroundImage;
  const flagsMedResp = await fetch('resources/ui/flags/eqp-united/flags_med.png');
  const flagBigResp = await fetch(`resources/ui/flags/eqp-united/flag_big_${(anyUnit?.flag)||1}.png`);
  return { uFlagStyle, flagsMedStatus: flagsMedResp.status, flagBigStatus: flagBigResp.status, unitFlag: anyUnit?.flag };
});
ok('unit-info flag path points at eqp-united', flagCheck.uFlagStyle.includes('eqp-united'), flagCheck.uFlagStyle);
ok('flags_med.png (eqp-united) is reachable', flagCheck.flagsMedStatus === 200, flagCheck.flagsMedStatus);
ok('flag_big_N.png (eqp-united) is reachable', flagCheck.flagBigStatus === 200, `status=${flagCheck.flagBigStatus} flag=${flagCheck.unitFlag}`);

// Close buy window
await page.evaluate(() => { window.game.ui.mainMenuButton('buy'); });

// Save/reload round trip through the NEW fmt-guarded export path.
const roundTrip = await page.evaluate(async () => {
  const exported = window.game.state.exportGameState();
  const hasFmt = exported.includes('"fmt":2');
  // Attempt reload into a fresh Game via restoreFromString (same path OSGlue.diskload uses).
  window.game.cleanup();
  const g2 = new (window.game.constructor)();
  g2.state = new (window.game.state.constructor)(g2);
  let restored = false;
  await new Promise((resolve) => {
    restored = g2.state.restoreFromString(exported, () => resolve());
    if (!restored) resolve();
  });
  window.game = g2;
  return { hasFmt, restored, name: g2.scenario ? g2.scenario.name : null };
});
ok('exported save includes fmt:2', roundTrip.hasFmt, roundTrip.hasFmt);
ok('restoreFromString accepts its own fresh export', roundTrip.restored === true, JSON.stringify(roundTrip));
ok('reloaded scenario name matches', roundTrip.name === 'RP German Blitz', roundTrip.name);

ok('no page errors', errs.length===0, errs.slice(0,3).join(' | '));
ok('no console errors', consoleErrs.length===0, consoleErrs.slice(0,5).join(' | '));

console.log('\n==== EQP-UNITED PROBE ====');
for (const [s,n,extra] of results) console.log(`${s}  ${n}${extra!==undefined?'  ['+extra+']':''}`);
if (errs.length) errs.slice(0,10).forEach(e=>console.log('  PAGEERROR: '+e));
if (consoleErrs.length) consoleErrs.slice(0,10).forEach(e=>console.log('  CONSOLE ERROR: '+e));
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
