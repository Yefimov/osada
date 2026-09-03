import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8814;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,160)));
const results=[]; const ok=(n,c)=>results.push([c?'PASS':'FAIL',n]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(400);

// open Options -> Settings
await page.evaluate(()=>{ window.game.ui.mainMenuButton('options'); });
await sleep(150);
await page.evaluate(()=>{ document.getElementById('settings').click(); });
await sleep(150);

const structure = await page.evaluate(()=>{
  const headers = [...document.querySelectorAll('.osada-settings-header__title')].map(h=>h.textContent);
  const observerHeader = [...document.querySelectorAll('.osada-settings-header')].find(h=>h.classList.contains('osada-settings-header--observer'));
  const caption = observerHeader ? observerHeader.querySelector('.osada-settings-header__caption')?.textContent : null;
  const confirmRow = document.getElementById('confirmEndTurn');
  return {
    headers,
    hasObserverCaption: caption === 'Affects game balance',
    confirmEndTurnPresent: !!confirmRow,
    confirmEndTurnDefaultOn: confirmRow ? confirmRow.innerHTML === 'C' : null,
    settingsVisible: getComputedStyle(document.getElementById('smSettings')).display !== 'none',
  };
});
ok('settings screen opened', structure.settingsVisible);
ok('4 named sections present (Map View/Gameplay/Sound/Observer Mode)',
   ['Map View','Gameplay','Sound','Observer Mode'].every(t=>structure.headers.includes(t)));
ok('Observer Mode section captioned "Affects game balance"', structure.hasObserverCaption);
ok('Confirm end of turn toggle present', structure.confirmEndTurnPresent);
ok('Confirm end of turn defaults ON', structure.confirmEndTurnDefaultOn===true);
if(!structure.settingsVisible) console.log('  DEBUG structure:', JSON.stringify(structure));

// toggle confirmEndTurn off, close settings, verify uiSettings actually flipped + End Turn skips confirm
const toggleFlow = await page.evaluate(async ()=>{
  document.getElementById('confirmEndTurn').click();
  const afterToggleGlyph = document.getElementById('confirmEndTurn').innerHTML;
  document.getElementById('smSetOkBut').click();
  await new Promise(res=>setTimeout(res,50));
  // find a ready unit count and click End Turn; with confirm off it should end immediately (no ✓/✗)
  window.game.ui.onEndTurnClick();
  await new Promise(res=>setTimeout(res,80));
  const et = document.getElementById('osadaEndTurn');
  const hasConfirmUI = !!et.querySelector('.osada-et__yes');
  return {afterToggleGlyph, hasConfirmUI};
});
ok('toggling confirmEndTurn off updates the checkbox glyph', toggleFlow.afterToggleGlyph==='c');
ok('with confirm OFF, End Turn has no inline confirm UI', !toggleFlow.hasConfirmUI);

// re-open settings, restore confirmEndTurn to ON, verify observer badge behavior
await page.evaluate(()=>{ window.game.ui.mainMenuButton('options'); });
await sleep(120);
await page.evaluate(()=>{ document.getElementById('settings').click(); });
await sleep(120);
const observerFlow = await page.evaluate(async ()=>{
  // restore confirmEndTurn (left OFF from the previous step) back to ON for report cleanliness
  if (document.getElementById('confirmEndTurn').innerHTML === 'c') document.getElementById('confirmEndTurn').click();
  // showHiddenVictoryHexes defaults to true (pre-existing, unrelated to Task 5), so the badge's
  // OR-of-two-flags condition is already satisfied out of the box — force a clean "both off"
  // baseline first so this test isolates noFOW specifically rather than assuming a default.
  if (document.getElementById('showHiddenVictoryHexes').innerHTML === 'C') document.getElementById('showHiddenVictoryHexes').click();
  if (document.getElementById('noFOW').innerHTML === 'C') document.getElementById('noFOW').click();
  await new Promise(res=>setTimeout(res,30));
  const before = getComputedStyle(document.getElementById('osadaObserverBadge')).display;
  document.getElementById('noFOW').click();
  await new Promise(res=>setTimeout(res,30));
  const afterOn = getComputedStyle(document.getElementById('osadaObserverBadge')).display;
  document.getElementById('noFOW').click(); // toggle back off
  await new Promise(res=>setTimeout(res,30));
  const afterOff = getComputedStyle(document.getElementById('osadaObserverBadge')).display;
  // restore showHiddenVictoryHexes to its original default (true) for report cleanliness
  document.getElementById('showHiddenVictoryHexes').click();
  document.getElementById('smSetOkBut').click();
  return {before, afterOn, afterOff};
});
ok('observer badge hidden when both observer flags are off', observerFlow.before==='none');
ok('observer badge appears when noFOW is enabled', observerFlow.afterOn!=='none');
ok('observer badge disappears when noFOW is disabled again', observerFlow.afterOff==='none');

ok('no page errors', errs.length===0);
console.log('\n==== TASK 5 PROBE ====');
for(const [s,n] of results) console.log(`${s}  ${n}`);
if(errs.length) errs.slice(0,5).forEach(e=>console.log('  ERR '+e));
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
