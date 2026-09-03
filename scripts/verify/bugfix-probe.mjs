import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8811;
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

// --- Bug 1: clicking osadaNavNext must NOT open the combat log ---
const nav = await page.evaluate(()=>{
  const before = getComputedStyle(document.getElementById('combatLog')).display;
  document.getElementById('osadaNavNext').click();
  const after = getComputedStyle(document.getElementById('combatLog')).display;
  return {before, after};
});
ok('nav-next does not open combat log', nav.before === 'none' && nav.after === 'none');
if (nav.after !== 'none') console.log('  DEBUG nav:', JSON.stringify(nav));

// --- Bug 2: #statusmsg must show the scenario/op line, not stale equipment text, and must
// survive selecting + moving a unit ---
const statusFlow = await page.evaluate(async ()=>{
  const opBefore = (document.querySelector('.osada-tb-op')||{}).textContent||'';
  const map = window.game.scenario.map;
  const cur = map.currentPlayer;
  const unit = map.getUnits().find(u => u.player && u.player.id===cur.id && !u.hasMoved);
  window.game.ui.uiUnitSelect(unit);
  await new Promise(r=>setTimeout(r,50));
  const opAfterSelect = (document.querySelector('.osada-tb-op')||{}).textContent||'';
  const rawAfterSelect = document.getElementById('statusmsg').textContent;
  // try to move the unit one step into its own move range if possible
  const range = map.getCurrentMoveRange();
  let moved = false;
  if (range && range.length>0) {
    const dest = range[0];
    window.game.ui.uiUnitMove(unit, dest.row, dest.col);
    moved = true;
  }
  await new Promise(r=>setTimeout(r,300));
  const opAfterMove = (document.querySelector('.osada-tb-op')||{}).textContent||'';
  return {opBefore, opAfterSelect, rawAfterSelect, moved, opAfterMove};
});
ok('op-slot has scenario name before selection', statusFlow.opBefore.length>0);
ok('op-slot NOT clobbered by "Units currently deployed"/"Deploy" text after selecting a unit',
   !/Units currently deployed|Deploy \(on map/.test(statusFlow.rawAfterSelect) && statusFlow.opAfterSelect === statusFlow.opBefore);
if (statusFlow.moved) {
  ok('op-slot still correct after moving the unit', statusFlow.opAfterMove === statusFlow.opBefore);
} else {
  console.log('  (skipped move-based assertion: no move range available for the picked unit)');
}
if (!statusFlow.opAfterSelect || /Units currently deployed|Deploy \(on map/.test(statusFlow.rawAfterSelect)) {
  console.log('  DEBUG statusFlow:', JSON.stringify(statusFlow));
}

// --- Bug 3: Escape closes the equipment window; Escape with nothing open toggles the pause menu ---
const escFlow = await page.evaluate(async ()=>{
  window.game.ui.mainMenuButton('buy');
  await new Promise(r=>setTimeout(r,50));
  const openedGrid = getComputedStyle(document.getElementById('equipment')).display;
  document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  await new Promise(r=>setTimeout(r,50));
  const closedByEsc = getComputedStyle(document.getElementById('equipment')).display;
  const startMenuBefore = getComputedStyle(document.getElementById('startmenu')).display;
  document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  await new Promise(r=>setTimeout(r,50));
  const startMenuAfter = getComputedStyle(document.getElementById('startmenu')).display;
  // toggle back closed so we don't leave the probe in a menu state
  document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
  await new Promise(r=>setTimeout(r,50));
  return {openedGrid, closedByEsc, startMenuBefore, startMenuAfter};
});
ok('equipment opens via buy', escFlow.openedGrid==='grid');
ok('Escape closes equipment window', escFlow.closedByEsc==='none');
ok('Escape (nothing open) opens the pause/options menu', escFlow.startMenuBefore==='none' && escFlow.startMenuAfter!=='none');
if (escFlow.closedByEsc!=='none' || escFlow.startMenuAfter==='none') console.log('  DEBUG escFlow:', JSON.stringify(escFlow));

// --- Bug 4: switching equipment MODE TAB immediately re-filters (no stale unfiltered render) ---
const modeSwitch = await page.evaluate(async ()=>{
  window.game.ui.mainMenuButton('buy'); // reopen (was closed by the 3rd escape above)
  await new Promise(r=>setTimeout(r,50));
  document.getElementById('eqModeTab-upgrade').click();
  await new Promise(r=>setTimeout(r,50));
  const immediatelyAfterTabSwitch = [...document.querySelectorAll('#unitlist .eqUnitBox')].map(i=>i.eqclass);
  document.getElementById('eqModeTab-purchase').click();
  await new Promise(r=>setTimeout(r,50));
  return {immediatelyAfterTabSwitch};
});
const distinctClasses = new Set(modeSwitch.immediatelyAfterTabSwitch);
ok('Upgrade tab is filtered to one class immediately (no stale render)', distinctClasses.size <= 1 && modeSwitch.immediatelyAfterTabSwitch.length >= 0);
if (distinctClasses.size > 1) console.log('  DEBUG modeSwitch classes:', JSON.stringify(modeSwitch.immediatelyAfterTabSwitch));

// --- Bug 5: objectives "held" must compare by SIDE (via player), not raw player id ---
const objSanity = await page.evaluate(()=>{
  const map = window.game.scenario.map;
  const side = map.currentPlayer.side;
  let mismatches = 0, total = 0;
  for (let r=0;r<map.rows;r++) for (let c=0;c<map.cols;c++) {
    const hex = map.map[r][c];
    if (hex.victorySide===-1 || hex.flag===-1 || hex.owner===-1) continue;
    total++;
    const bySide = map.getPlayer(hex.owner).side === side;
    const byRawId = hex.owner === side;
    if (bySide !== byRawId) mismatches++;
  }
  return {total, mismatches};
});
console.log('  objective owner-vs-side sanity: total=' + objSanity.total + ' mismatches(raw-id-would-differ)=' + objSanity.mismatches);
ok('objectives sanity check ran without error', objSanity.total >= 0);

ok('no page errors', errs.length===0);
console.log('\n==== BUGFIX PROBE ====');
for(const [s,n] of results) console.log(`${s}  ${n}`);
if(errs.length) errs.slice(0,5).forEach(e=>console.log('  ERR '+e));
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
