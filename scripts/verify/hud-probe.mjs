import http from 'http'; import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core'; import { getChromePath } from 'chrome-launcher';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname,'..','..','build','dist','js','developmentExecutable');
const PORT = 8804;
const MIME={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json','.xml':'application/xml','.png':'image/png','.jpg':'image/jpeg','.ttf':'font/ttf','.wav':'audio/wav','.mp3':'audio/mpeg','.ogg':'audio/ogg','.gif':'image/gif','.svg':'image/svg+xml','.ico':'image/x-icon'};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=await new Promise(res=>{const s=http.createServer((rq,rs)=>{const raw=rq.url.split('?')[0];const fp=path.join(DIST,raw==='/'?'index.html':raw);fs.readFile(fp,(e,d)=>{if(e){rs.writeHead(404);rs.end();return;}rs.writeHead(200,{'Content-Type':MIME[path.extname(fp).toLowerCase()]||'application/octet-stream'});rs.end(d);});});s.listen(PORT,()=>res(s));});
const browser=await puppeteer.launch({executablePath:getChromePath(),headless:'new',args:['--no-sandbox']});
const page=await browser.newPage(); await page.setViewport({width:1920,height:1080});
const errs=[]; page.on('pageerror',e=>errs.push(e.message.slice(0,150)));
const results=[]; const ok=(n,c)=>results.push([c?'PASS':'FAIL',n]);

await page.goto(`http://localhost:${PORT}/`,{waitUntil:'networkidle2'}); await sleep(1600);
ok('charset UTF-8', await page.evaluate(()=>document.characterSet==='UTF-8'));

await page.evaluate(()=>{window.game.campaign=null;window.game.newScenario('drpzop01.xml','x');});
await sleep(3000);
await page.evaluate(()=>{document.getElementById('startmenu').style.display='none';document.getElementById('uiokbut').click();});
await sleep(400);

const probe=await page.evaluate(()=>{
  const out={};
  // 1. equipment must NOT be open at scenario start
  out.equipDisplay=getComputedStyle(document.getElementById('equipment')).display;
  // 2. #menu rail is dissolved (Task 1); sidebar sits below the 40px top bar, flush right
  out.menuDisplay=getComputedStyle(document.getElementById('menu')).display;
  const sb=document.getElementById('osada-sidebar').getBoundingClientRect();
  out.sidebarRect=[Math.round(sb.left),Math.round(sb.top),Math.round(sb.width)];
  out.sidebarRight=Math.round(sb.right);
  // 3. toggles row present, no mojibake
  out.gridLabel=document.getElementById('hex').textContent;
  out.airLabel=document.getElementById('air').textContent;
  // 4. objectives: names present, inside sidebar, mark glyph sane (check / flag, not the old star)
  const objs=[...document.querySelectorAll('#osadaObjectives .osada-obj')].slice(0,4).map(o=>{
    const n=o.querySelector('.osada-obj__name'); const r=n.getBoundingClientRect();
    return {text:n.textContent, left:Math.round(r.left), width:Math.round(r.width), mark:o.querySelector('.osada-obj__mark').textContent};
  });
  out.objs=objs;
  out.railCounter=document.getElementById('osadaRailObjCounter').textContent;
  // 5. log panel starts empty
  out.logEmptyText=(document.querySelector('#osadaLog .osada-side-empty')||{}).textContent||'';
  return out;
});
ok('equipment closed at scenario start', probe.equipDisplay==='none');
ok('#menu rail dissolved', probe.menuDisplay==='none');
ok('sidebar below topbar, flush right', probe.sidebarRect[1]===40 && probe.sidebarRight===1920);
ok('no mojibake in toggle labels', probe.gridLabel==='Grid' && probe.airLabel==='Air');
ok('objective names present + inside sidebar', probe.objs.length>0 && probe.objs.every(o=>o.text.length>0 && o.width>10 && o.left>=probe.sidebarRect[0]));
ok('objective mark is check/flag glyph', probe.objs.every(o=>o.mark==='✓'||o.mark==='⚑'));
ok('rail objectives counter is N/M', /^\d+\/\d+$/.test(probe.railCounter));
ok('log panel starts with empty-state text', probe.logEmptyText==='No events yet');

// 6. Grid/Air toggles actually flip uiSettings + [selected] attribute
const toggles=await page.evaluate(()=>{
  const before={hex:window.uiSettings?window.uiSettings.hexGrid:null};
  document.getElementById('hex').click();
  const hexSelected=document.getElementById('hex').hasAttribute('selected');
  document.getElementById('air').click();
  const airSelected=document.getElementById('air').hasAttribute('selected');
  // revert so later checks aren't affected by air-mode changing click behavior
  document.getElementById('air').click();
  return {hexSelected,airSelected};
});
ok('Grid toggle sets [selected]', toggles.hexSelected===true);
ok('Air toggle sets [selected]', toggles.airSelected===true);

// 7. select unit -> info auto-shows (existing behavior, unrelated to sidebar rework)
const sel=await page.evaluate(()=>{
  const g=window.game;const map=g.scenario.map;const cur=map.currentPlayer;
  const u=map.getUnits().find(x=>x.player&&x.player.id===cur.id); g.ui.uiUnitSelect(u);
  return getComputedStyle(document.getElementById('unit-info')).display!=='none';
});
ok('unit info auto-shows', sel);

// 8. end turn -> confirm -> log gets a "Turn n/m — <side> begins" line (exercises the
// turn-change HudLog hook). Ending a turn advances to the NEXT PLAYER in order, which may
// be a second player on the same side before wrapping — so only turn/side text is asserted,
// not a specific turn number.
const turnLog=await page.evaluate(async ()=>{
  window.game.ui.onEndTurnClick();
  await new Promise(r=>setTimeout(r,50));
  const yes=document.querySelector('#osadaEndTurn .osada-et__yes');
  if(yes) yes.click(); else document.getElementById('osadaEndTurn').click();
  await new Promise(r=>setTimeout(r,300));
  return document.getElementById('osadaLog').textContent;
});
ok('log records the turn change', /Turn \d+\/\d+.*begins/.test(turnLog));
if(!/Turn \d+\/\d+.*begins/.test(turnLog)) console.log('  DEBUG turnLog:', JSON.stringify(turnLog));

// 9. collapse rail: whole-sidebar collapse + expand, counter/dot visible only when collapsed
const collapse=await page.evaluate(async ()=>{
  document.getElementById('osadaSideToggle').click();
  const collapsedClass=document.getElementById('osada-sidebar').classList.contains('osada-sidebar--collapsed');
  const railVisible=getComputedStyle(document.getElementById('osadaSideRail')).display!=='none';
  const bodyHidden=getComputedStyle(document.getElementById('osadaSideBody')).display==='none';
  const counterVisible=getComputedStyle(document.getElementById('osadaRailObjCounter')).display!=='none';
  const storedCollapsed=localStorage.getItem('osada-sidebar-collapsed');
  document.getElementById('osadaRailExpand').click();
  const expandedBack=!document.getElementById('osada-sidebar').classList.contains('osada-sidebar--collapsed');
  return {collapsedClass,railVisible,bodyHidden,counterVisible,storedCollapsed,expandedBack};
});
ok('collapse adds collapsed class + hides body', collapse.collapsedClass && collapse.bodyHidden);
ok('collapse shows rail with counter', collapse.railVisible && collapse.counterVisible);
ok('collapse state persisted to localStorage', collapse.storedCollapsed==='1');
ok('expand restores sidebar', collapse.expandedBack);

// 10. open equipment via buy -> grid (Task 0 CSS-grid rebuild); close -> none
const eq=await page.evaluate(()=>{
  window.game.ui.mainMenuButton('buy');
  const open=getComputedStyle(document.getElementById('equipment')).display;
  window.game.ui.mainMenuButton('buy');
  const closed=getComputedStyle(document.getElementById('equipment')).display;
  return {open,closed};
});
ok('equipment opens as grid via Buy', eq.open==='grid');
ok('equipment closes again', eq.closed==='none');

ok('no page errors', errs.length===0);
console.log('\n==== PROBE ====');
for(const [s,n] of results) console.log(`${s}  ${n}`);
if(errs.length) errs.slice(0,5).forEach(e=>console.log('  ERR '+e));
console.log(`${results.filter(r=>r[0]==='PASS').length}/${results.length} passed`);
await browser.close(); server.close();
process.exit(results.some(r=>r[0]==='FAIL')?1:0);
